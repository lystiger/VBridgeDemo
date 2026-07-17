package com.example.demovbridge.pipeline

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.demovbridge.asr.SherpaAsr
import com.example.demovbridge.audio.AudioCapture
import com.example.demovbridge.audio.AudioPlayback
import com.example.demovbridge.translation.MlKitTranslator
import com.example.demovbridge.tts.SherpaTts
import com.example.demovbridge.ui.conversation.ConversationTurn
import com.example.demovbridge.ui.conversation.TurnDirection
import com.example.demovbridge.ui.conversation.TurnStatus
import com.example.demovbridge.utils.ResourceUtils
import com.example.demovbridge.vad.SherpaVad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MeetingViewModel(
    private val context: Context
) : ViewModel() {
    private val assets = context.assets
    private var pipeline: InterpreterPipeline? = null

    private val _isReady = MutableStateFlow(false)
    val isReady: StateFlow<Boolean> = _isReady.asStateFlow()

    private val _pipelineEvents = MutableSharedFlow<PipelineEvent>(extraBufferCapacity = 64)
    val pipelineEvents: SharedFlow<PipelineEvent> = _pipelineEvents.asSharedFlow()

    private val _turns = MutableStateFlow<List<ConversationTurn>>(emptyList())
    val turns: StateFlow<List<ConversationTurn>> = _turns.asStateFlow()

    private val _currentDirection = MutableStateFlow(Direction.ViToEn)
    val currentDirection: StateFlow<Direction> = _currentDirection.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    initializePipeline()
                }
                _isReady.value = true
                
                pipeline?.events?.collect { event ->
                    _pipelineEvents.emit(event)
                    handlePipelineEvent(event)
                }
            } catch (e: Exception) {
                // Log the error and maybe show an error state in UI
                e.printStackTrace()
            }
        }
    }

    private fun initializePipeline() {
        android.util.Log.d("VBridge", "Initializing Pipeline components...")
        val capture = AudioCapture()
        
        android.util.Log.d("VBridge", "Loading VAD...")
        val vad = SherpaVad(assets, "vad/silero_vad.onnx")
        
        android.util.Log.d("VBridge", "Loading ASR Vi...")
        val asrVi = SherpaAsr(
            assets,
            encoderPath = "asr-vi/encoder.onnx",
            decoderPath = "asr-vi/decoder.onnx",
            joinerPath = "asr-vi/joiner.onnx",
            tokensPath = "asr-vi/tokens.txt"
        )
        
        android.util.Log.d("VBridge", "Loading ASR En...")
        val asrEn = SherpaAsr(
            assets,
            encoderPath = "asr-en/encoder.onnx",
            decoderPath = "asr-en/decoder.onnx",
            joinerPath = "asr-en/joiner.onnx",
            tokensPath = "asr-en/tokens.txt"
        )
        
        android.util.Log.d("VBridge", "Loading Translator...")
        val translator = MlKitTranslator()
        
        android.util.Log.d("VBridge", "Preparing TTS assets...")
        val ttsEnDir = File(context.filesDir, "tts-en")
        ResourceUtils.copyAssetsDir(context, "tts-en/espeak-ng-data", File(ttsEnDir, "espeak-ng-data"))
        
        val ttsViDir = File(context.filesDir, "tts-vi")
        ResourceUtils.copyAssetsDir(context, "tts-vi/espeak-ng-data", File(ttsViDir, "espeak-ng-data"))

        android.util.Log.d("VBridge", "Loading TTS En...")
        val ttsEn = SherpaTts(
            assets,
            modelPath = "tts-en/vits.onnx",
            tokensPath = "tts-en/tokens.txt",
            dataDir = File(ttsEnDir, "espeak-ng-data").absolutePath
        )
        
        android.util.Log.d("VBridge", "Loading TTS Vi...")
        val ttsVi = SherpaTts(
            assets,
            modelPath = "tts-vi/vits.onnx",
            tokensPath = "tts-vi/tokens.txt",
            dataDir = File(ttsViDir, "espeak-ng-data").absolutePath
        )
        
        android.util.Log.d("VBridge", "Loading Playback...")
        val playback = AudioPlayback()

        pipeline = InterpreterPipeline(
            capture, vad, asrVi, asrEn, translator, ttsVi, ttsEn, playback
        )
        android.util.Log.d("VBridge", "Pipeline initialization complete.")
    }

    private fun handlePipelineEvent(event: PipelineEvent) {
        val currentList = _turns.value.toMutableList()
        val index = currentList.indexOfFirst { it.id == event.turnId }

        when (event) {
            is PipelineEvent.SpeechEnded -> {
                currentList.add(
                    ConversationTurn(
                        id = event.turnId,
                        direction = if (_currentDirection.value == Direction.ViToEn) TurnDirection.ViToEn else TurnDirection.EnToVi,
                        sourceText = "...",
                        translatedText = "",
                        status = TurnStatus.Transcribing
                    )
                )
            }
            is PipelineEvent.Transcribed -> {
                if (index >= 0) {
                    currentList[index] = currentList[index].copy(
                        sourceText = event.text,
                        status = TurnStatus.Translating
                    )
                }
            }
            is PipelineEvent.Translated -> {
                if (index >= 0) {
                    currentList[index] = currentList[index].copy(
                        translatedText = event.translatedText,
                        status = TurnStatus.Complete
                    )
                }
            }
            is PipelineEvent.Failed -> {
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

    fun startPipeline() {
        pipeline?.start()
    }

    fun stopPipeline() {
        pipeline?.stop()
    }

    fun toggleDirection() {
        val next = if (_currentDirection.value == Direction.ViToEn) Direction.EnToVi else Direction.ViToEn
        _currentDirection.value = next
        pipeline?.currentDirection = next
    }

    fun retryTurn(turnId: String) {
        // Implement retry logic if needed, e.g. re-sending to MT or ASR
    }
}
