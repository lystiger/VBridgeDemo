package com.example.demovbridge.pipeline

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.demovbridge.asr.SherpaAsr
import com.example.demovbridge.audio.AudioCapture
import com.example.demovbridge.audio.AudioPlayback
import com.example.demovbridge.translation.MlKitTranslator
import com.example.demovbridge.tts.SherpaTts
import com.example.demovbridge.network.NetworkEvent
import com.example.demovbridge.network.VBridgeSocket
import com.example.demovbridge.data.ParticipantConfig
import com.example.demovbridge.ui.conversation.ConversationTurn
import com.example.demovbridge.ui.conversation.TurnDirection
import com.example.demovbridge.ui.conversation.TurnStatus
import com.example.demovbridge.utils.ResourceUtils
import com.example.demovbridge.vad.SherpaVad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

enum class ConnectivityMode { Solo, Room }
enum class MtEngine { OnDevice, Remote }

class MeetingViewModel(
    private val context: Context,
    private val config: ParticipantConfig
) : ViewModel() {
    private val assets = context.assets
    private var pipeline: InterpreterPipeline? = null
    private var translator: com.example.demovbridge.translation.Translator? = null
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

    private val _mode = MutableStateFlow(ConnectivityMode.Room)
    val mode: StateFlow<ConnectivityMode> = _mode.asStateFlow()

    private val _mtEngine = MutableStateFlow(MtEngine.OnDevice)
    val mtEngine: StateFlow<MtEngine> = _mtEngine.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    initializePipeline()
                }
                _isReady.value = true
                
                launch {
                    diagnostics.telemetry.collect { _telemetry.value = it }
                }

                launch {
                    network.events.collect { _connectionState.value = it }
                }

                pipeline?.events?.collect { event ->
                    _pipelineEvents.emit(event)
                    handlePipelineEvent(event)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                _meetingState.value = MeetingState.Error(e.message ?: "Initialization failed")
            }
        }
    }

    private fun initializePipeline() {
        network.connect(config.roomId)
        
        val capture = AudioCapture()
        val vad = SherpaVad(assets, "vad/silero_vad.onnx")
        val asrVi = SherpaAsr(
            assets,
            encoderPath = "asr-vi/encoder.onnx",
            decoderPath = "asr-vi/decoder.onnx",
            joinerPath = "asr-vi/joiner.onnx",
            tokensPath = "asr-vi/tokens.txt"
        )
        val asrEn = SherpaAsr(
            assets,
            encoderPath = "asr-en/encoder.onnx",
            decoderPath = "asr-en/decoder.onnx",
            joinerPath = "asr-en/joiner.onnx",
            tokensPath = "asr-en/tokens.txt"
        )
        val mlkitTranslator = MlKitTranslator()
        this.translator = mlkitTranslator
        
        val ttsEnDir = File(context.filesDir, "tts-en")
        ResourceUtils.copyAssetsDir(context, "tts-en/espeak-ng-data", File(ttsEnDir, "espeak-ng-data"))
        
        val ttsViDir = File(context.filesDir, "tts-vi")
        ResourceUtils.copyAssetsDir(context, "tts-vi/espeak-ng-data", File(ttsViDir, "espeak-ng-data"))

        val ttsEn = SherpaTts(
            assets,
            modelPath = "tts-en/vits.onnx",
            tokensPath = "tts-en/tokens.txt",
            dataDir = File(ttsEnDir, "espeak-ng-data").absolutePath
        )
        
        val ttsVi = SherpaTts(
            assets,
            modelPath = "tts-vi/vits.onnx",
            tokensPath = "tts-vi/tokens.txt",
            dataDir = File(ttsViDir, "espeak-ng-data").absolutePath
        )
        
        val playback = AudioPlayback()

        pipeline = InterpreterPipeline(
            capture, vad, asrVi, asrEn, mlkitTranslator, ttsVi, ttsEn, playback, network,
            roomId = config.roomId,
            localParticipantId = config.participantId,
            localParticipantName = config.displayName,
            diagnostics = diagnostics
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
                        sourceText = if (currentList[index].sourceText == "..." || currentList[index].sourceText.isBlank()) event.sourceText else currentList[index].sourceText
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
                if (!event.isLocal) {
                    _meetingState.value = MeetingState.PlayingRemoteTts
                }
            }
            is PipelineEvent.PlaybackCompleted -> {
                if (!event.isLocal) {
                    _meetingState.value = MeetingState.Idle
                }
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
        pipeline?.stop()
        network.disconnect()
    }

    fun toggleDirection() {
        val next = if (_currentDirection.value == Direction.ViToEn) Direction.EnToVi else Direction.ViToEn
        _currentDirection.value = next
        pipeline?.currentDirection = next
    }

    fun setConnectivityMode(newMode: ConnectivityMode) {
        when (newMode) {
            ConnectivityMode.Solo -> network.disconnect()
            ConnectivityMode.Room -> network.connect(config.roomId)
        }
        _mode.value = newMode
    }

    fun setMtEngine(engine: MtEngine) {
        _mtEngine.value = engine
        // Note: In a real app, we might want to recreate the pipeline here
        // or use a delegating translator. For now we just update the state.
    }

    fun retryTurn(turnId: String) {
        // Implement retry logic if needed
    }

    override fun onCleared() {
        super.onCleared()
        pipeline?.stop()
        translator?.close()
        network.destroy()
        diagnostics.stop()
    }
}
