package com.example.demovbridge.pipeline

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.demovbridge.asr.WhisperAsr
import com.example.demovbridge.audio.AudioCapture
import com.example.demovbridge.audio.AudioPlayback
import com.example.demovbridge.translation.MlKitTranslator
import com.example.demovbridge.translation.ServerTranslator
import com.example.demovbridge.translation.Translator
import com.example.demovbridge.tts.AndroidTts
import com.example.demovbridge.network.NetworkEvent
import com.example.demovbridge.network.VBridgeSocket
import com.example.demovbridge.data.ParticipantConfig
import com.example.demovbridge.ui.conversation.ConversationTurn
import com.example.demovbridge.ui.conversation.TurnDirection
import com.example.demovbridge.ui.conversation.TurnStatus
import com.example.demovbridge.utils.ResourceUtils
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
    
sealed interface MeetingState {
    data object Idle : MeetingState
    data object Recording : MeetingState
    data object ProcessingAsr : MeetingState
    data object Translating : MeetingState
    data object Sending : MeetingState
    data object PlayingRemoteTts : MeetingState
    data class Error(val message: String) : MeetingState
}

class MeetingViewModel(
    private val context: Context,
    private val config: ParticipantConfig
) : ViewModel() {
    private var pipeline: InterpreterPipeline? = null
    private var asr: WhisperAsr? = null
    private var tts: AndroidTts? = null
    private val network = VBridgeSocket()
    private val diagnostics = PipelineDiagnostics(context)

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _telemetry = MutableStateFlow(PipelineTelemetry())
    val telemetry: StateFlow<PipelineTelemetry> = _telemetry.asStateFlow()

    private val _meetingState = MutableStateFlow<MeetingState>(MeetingState.Idle)
    val meetingState: StateFlow<MeetingState> = _meetingState.asStateFlow()

    private val _pipelineEvents = MutableSharedFlow<PipelineEvent>(extraBufferCapacity = 64)
    val pipelineEvents: SharedFlow<PipelineEvent> = _pipelineEvents.asSharedFlow()

    private val _turns = MutableStateFlow<List<ConversationTurn>>(emptyList())
    val turns: StateFlow<List<ConversationTurn>> = _turns.asStateFlow()

    private val _currentDirection = MutableStateFlow(
        if (config.sourceLanguage == "vi") Direction.ViToEn else Direction.EnToVi
    )
    val currentDirection: StateFlow<Direction> = _currentDirection.asStateFlow()

    private val _connectionState = MutableStateFlow<NetworkEvent>(NetworkEvent.Disconnected)
    val connectionState: StateFlow<NetworkEvent> = _connectionState.asStateFlow()

    private val _isOffline = MutableStateFlow(false)
    val isOffline: StateFlow<Boolean> = _isOffline.asStateFlow()

    private var pipelineJob: Job? = null
    private var isInitializing = false

    init {
        startPipeline()
        
        viewModelScope.launch {
            diagnostics.telemetry.collect { _telemetry.value = it }
        }

        viewModelScope.launch {
            network.events.collect { _connectionState.value = it }
        }
    }

    private fun startPipeline() {
        if (isInitializing) return
        isInitializing = true
        _isReady.value = false
        
        pipelineJob?.cancel()
        pipelineJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    initializeInternal()
                }
                _isReady.value = true
                
                pipeline?.events?.collect { event ->
                    _pipelineEvents.emit(event)
                    handlePipelineEvent(event)
                }
            } catch (e: Exception) {
                Log.e("MeetingViewModel", "Failed to start pipeline", e)
                _meetingState.value = MeetingState.Error(e.message ?: "Initialization failed")
            } finally {
                isInitializing = false
            }
        }
    }

    private suspend fun initializeInternal() {
        val isOfflineMode = _isOffline.value
        
        if (!isOfflineMode) {
            network.connect(config.roomId)
        } else {
            network.disconnect()
        }
        
        // Ensure model is present
        val modelFile = File(context.filesDir, "ggml-small-q5_1.bin")
        if (!modelFile.exists()) {
            // Try copying from assets if available
            try {
                ResourceUtils.copyAssetsDir(context, "models", context.filesDir)
            } catch (e: Exception) {
                Log.w("MeetingViewModel", "Could not copy models from assets")
            }
        }

        asr?.release()
        val newAsr = WhisperAsr(context, modelFile.absolutePath)
        asr = newAsr
        
        val translator: Translator = if (isOfflineMode) MlKitTranslator() else ServerTranslator()
        
        tts?.shutdown()
        val newTts = AndroidTts(context)
        tts = newTts
        
        val playback = AudioPlayback()
        val capture = AudioCapture()

        pipeline?.stop()
        pipeline = InterpreterPipeline(
            capture, newAsr, translator, newTts, playback, network,
            roomId = config.roomId,
            localParticipantId = config.participantId,
            localParticipantName = config.displayName,
            diagnostics = diagnostics,
            isOffline = isOfflineMode
        ).apply {
            currentDirection = _currentDirection.value
            start()
        }
    }

    private fun handlePipelineEvent(event: PipelineEvent) {
        val currentList = _turns.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == event.turnId }

        when (event) {
            is PipelineEvent.SpeechStarted -> {
                _meetingState.value = MeetingState.Recording
                currentList.add(
                    ConversationTurn(
                        id = event.turnId,
                        speakerId = config.participantId,
                        speakerName = config.displayName,
                        isLocal = true,
                        direction = if (_currentDirection.value == Direction.ViToEn) TurnDirection.ViToEn else TurnDirection.EnToVi,
                        sourceText = "...",
                        translatedText = "",
                        status = TurnStatus.Transcribing
                    )
                )
            }
            is PipelineEvent.SpeechEnded -> {
                _meetingState.value = MeetingState.ProcessingAsr
            }
            is PipelineEvent.Transcribed -> {
                if (event.isLocal) _meetingState.value = MeetingState.Translating
                if (index >= 0) {
                    currentList[index] = currentList[index].copy(
                        sourceText = event.text,
                        status = TurnStatus.Translating
                    )
                }
            }
            is PipelineEvent.Translated -> {
                if (event.isLocal) _meetingState.value = MeetingState.Idle
                if (index >= 0) {
                    currentList[index] = currentList[index].copy(
                        translatedText = event.translatedText,
                        status = TurnStatus.Complete,
                        sourceText = if (currentList[index].sourceText == "...") event.sourceText else currentList[index].sourceText
                    )
                } else {
                    currentList.add(
                        ConversationTurn(
                            id = event.turnId,
                            speakerId = if (event.isLocal) config.participantId else "remote",
                            speakerName = event.speakerName ?: "Remote", 
                            isLocal = event.isLocal,
                            direction = if (event.direction == Direction.ViToEn) TurnDirection.ViToEn else TurnDirection.EnToVi,
                            sourceText = event.sourceText,
                            translatedText = event.translatedText,
                            status = TurnStatus.Complete
                        )
                    )
                }
            }
            is PipelineEvent.PlaybackStarted -> {
                if (!event.isLocal) _meetingState.value = MeetingState.PlayingRemoteTts
            }
            is PipelineEvent.PlaybackCompleted -> {
                if (!event.isLocal) _meetingState.value = MeetingState.Idle
            }
            is PipelineEvent.Failed -> {
                _meetingState.value = MeetingState.Idle
                if (index >= 0) {
                    currentList[index] = currentList[index].copy(
                        status = TurnStatus.Error,
                        errorMessage = event.message
                    )
                }
            }
            else -> {}
        }
        _turns.value = currentList
    }

    fun startRecording() {
        pipeline?.startRecording()
    }

    fun stopRecording() {
        pipeline?.stopRecording()
    }

    fun stopPipeline() {
        pipelineJob?.cancel()
        pipeline?.stop()
        network.disconnect()
        _isReady.value = false
    }

    fun toggleDirection() {
        val next = if (_currentDirection.value == Direction.ViToEn) Direction.EnToVi else Direction.ViToEn
        _currentDirection.value = next
        pipeline?.currentDirection = next
    }

    fun toggleOffline() {
        if (isInitializing) return
        _isOffline.value = !_isOffline.value
        stopPipeline()
        startPipeline()
    }

    fun retryTurn(turnId: String) {
        // Retry logic could be added here
    }

    fun destroy() {
        pipelineJob?.cancel()
        pipeline?.stop()
        network.destroy()
        diagnostics.stop()
        tts?.shutdown()
        viewModelScope.launch {
            asr?.release()
        }
    }

    override fun onCleared() {
        super.onCleared()
        destroy()
    }
}
