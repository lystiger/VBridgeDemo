package com.example.demovbridge.pipeline

import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.demovbridge.asr.SherpaAsr
import com.example.demovbridge.audio.AudioCapture
import com.example.demovbridge.audio.AudioPlayback
import com.example.demovbridge.translation.MlKitTranslator
import com.example.demovbridge.translation.DelegatingTranslator
import com.example.demovbridge.translation.LanServerTranslator
import com.example.demovbridge.net.LanFallbackClient
import com.example.demovbridge.BuildConfig
import com.example.demovbridge.tts.AndroidTtsSynthesizer
import com.example.demovbridge.network.NetworkEvent
import com.example.demovbridge.network.VBridgeSocket
import com.example.demovbridge.network.DelegatingTranslationTransport
import com.example.demovbridge.network.bluetooth.BluetoothConnectionManager
import com.example.demovbridge.network.bluetooth.BluetoothTranslationTransport
import com.example.demovbridge.data.ParticipantConfig
import com.example.demovbridge.ui.conversation.ConversationTurn
import com.example.demovbridge.ui.conversation.TurnDirection
import com.example.demovbridge.ui.conversation.TurnStatus
import com.example.demovbridge.vad.SherpaVad
import com.example.demovbridge.network.ConnectivityMonitor
import com.example.demovbridge.audio.BluetoothAudioManager
import com.example.demovbridge.translation.Glossary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface MeetingState {
    data object Idle : MeetingState
    data object Listening : MeetingState
    data object Recording : MeetingState
    data object ProcessingAsr : MeetingState
    data object Translating : MeetingState
    data object Sending : MeetingState
    data object PlayingRemoteTts : MeetingState
    data class Error(val message: String) : MeetingState
}

enum class ConnectivityMode { Solo, Bluetooth, Room }
enum class MtEngine { OnDevice, Remote }
enum class Floor { Open, LocalSpeaking, RemoteSpeaking }
enum class CaptureMode { HandsOn, HandsFree }

class MeetingViewModel(
    private val context: Context,
    private val config: ParticipantConfig
) : ViewModel() {
    private val assets = context.assets
    private var pipeline: InterpreterPipeline? = null
    private var translator: com.example.demovbridge.translation.Translator? = null
    private var switchableTranslator: DelegatingTranslator? = null
    private val roomTransport = VBridgeSocket()
    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val btManager = BluetoothConnectionManager(bluetoothManager.adapter)
    private val btTransport = BluetoothTranslationTransport(btManager)
    private val soloTransport = NoOpTransport()
    private val delegatingTransport = DelegatingTranslationTransport()
    
    private val connectivityMonitor = ConnectivityMonitor(context)
    private val bluetoothAudioManager = BluetoothAudioManager(context)
    private val glossary = Glossary()
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

    private val _isOnline = MutableStateFlow(false)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _isBluetoothActive = MutableStateFlow(false)
    val isBluetoothActive: StateFlow<Boolean> = _isBluetoothActive.asStateFlow()

    private val _mode = MutableStateFlow(ConnectivityMode.Room)
    val mode: StateFlow<ConnectivityMode> = _mode.asStateFlow()

    private val _mtEngine = MutableStateFlow(MtEngine.OnDevice)
    val mtEngine: StateFlow<MtEngine> = _mtEngine.asStateFlow()

    private val _floor = MutableStateFlow(Floor.Open)
    val floor: StateFlow<Floor> = _floor.asStateFlow()

    private val _captureMode = MutableStateFlow(CaptureMode.HandsOn)
    val captureMode: StateFlow<CaptureMode> = _captureMode.asStateFlow()

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
                    delegatingTransport.events.collect { _connectionState.value = it }
                }

                launch {
                    connectivityMonitor.isOnline.collect { isOnline ->
                        _isOnline.value = isOnline
                        switchableTranslator?.useRemote = isOnline
                        _mtEngine.value = if (isOnline) MtEngine.Remote else MtEngine.OnDevice
                    }
                }

                launch {
                    bluetoothAudioManager.isBluetoothConnected.collect { active ->
                        _isBluetoothActive.value = active
                        if (active) {
                            bluetoothAudioManager.startSco()
                        } else {
                            bluetoothAudioManager.stopSco()
                        }
                    }
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
        updateTransport()

        val capture = AudioCapture()
        val vad = SherpaVad(assets, "vad/silero_vad.onnx").apply {
            updateParameters(
                minSilenceDuration = 0.8f,
                minSpeechDuration = 0.2f,
                threshold = 0.4f
            )
        }
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
        val delegatingTranslator = DelegatingTranslator(
            onDevice = mlkitTranslator,
            remote = LanServerTranslator(LanFallbackClient(BuildConfig.VBRIDGE_LAN_URL))
        )
        switchableTranslator = delegatingTranslator
        this.translator = delegatingTranslator

        val ttsEn = AndroidTtsSynthesizer(context, java.util.Locale.ENGLISH)
        val ttsVi = AndroidTtsSynthesizer(context, java.util.Locale("vi", "VN"))

        val playback = AudioPlayback()

        pipeline = InterpreterPipeline(
            capture, vad, asrVi, asrEn, delegatingTranslator, ttsVi, ttsEn, playback, delegatingTransport,
            glossary = glossary,
            roomId = config.roomId,
            localParticipantId = config.participantId,
            localParticipantName = config.displayName,
            diagnostics = diagnostics,
            isOnline = _isOnline
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
                // Deduplicate SpeechStarted in case of "late" events
                if (currentList.any { it.id == event.turnId }) return@handlePipelineEvent

                _meetingState.value = MeetingState.Recording
                _floor.value = Floor.LocalSpeaking
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
                        direction = if (event.direction == Direction.ViToEn) TurnDirection.ViToEn else TurnDirection.EnToVi,
                        status = TurnStatus.Translating
                    )
                }
            }
            is PipelineEvent.Translated -> {
                if (event.isLocal) {
                    // Only return to Listening if the user hasn't manually toggled OFF
                    if (_meetingState.value != MeetingState.Idle) {
                        _meetingState.value = MeetingState.Listening
                    }
                    _floor.value = Floor.Open
                }
                if (index >= 0) {
                    currentList[index] = currentList[index].copy(
                        translatedText = event.translatedText,
                        direction = if (event.direction == Direction.ViToEn) TurnDirection.ViToEn else TurnDirection.EnToVi,
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
                    _floor.value = Floor.RemoteSpeaking
                }
            }
            is PipelineEvent.PlaybackCompleted -> {
                if (!event.isLocal) {
                    _meetingState.value = if (_meetingState.value != MeetingState.Idle) MeetingState.Listening else MeetingState.Idle
                    _floor.value = Floor.Open
                }
            }
            is PipelineEvent.Failed -> {
                _meetingState.value = if (_meetingState.value != MeetingState.Idle) MeetingState.Listening else MeetingState.Idle
                _floor.value = Floor.Open
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
        if (_meetingState.value != MeetingState.Idle) return
        _meetingState.value = MeetingState.Listening
        pipeline?.startRecording()
    }

    fun stopRecording() {
        if (_meetingState.value == MeetingState.Idle) return
        _meetingState.value = MeetingState.Idle
        pipeline?.stopRecording()
    }

    fun stopPipeline() {
        pipeline?.stop()
        delegatingTransport.disconnect()
    }

    fun toggleDirection() {
        val next = if (_currentDirection.value == Direction.ViToEn) Direction.EnToVi else Direction.ViToEn
        _currentDirection.value = next
        pipeline?.currentDirection = next
    }

    fun setConnectivityMode(newMode: ConnectivityMode) {
        _mode.value = newMode
        updateTransport()
    }

    private fun updateTransport() {
        val transport = when (_mode.value) {
            ConnectivityMode.Solo -> soloTransport
            ConnectivityMode.Bluetooth -> btTransport
            ConnectivityMode.Room -> roomTransport
        }
        delegatingTransport.setTransport(transport)

        if (_mode.value == ConnectivityMode.Room) {
            roomTransport.connect(config.roomId)
        } else {
            roomTransport.disconnect()
        }
        
        if (_mode.value != ConnectivityMode.Bluetooth) {
            btManager.stop()
        }
    }

    fun setMtEngine(engine: MtEngine) {
        switchableTranslator?.useRemote = engine == MtEngine.Remote
        _mtEngine.value = engine
    }

    fun setCaptureMode(mode: CaptureMode) {
        if (_meetingState.value == MeetingState.Recording) stopRecording()
        _captureMode.value = mode
    }

    fun retryTurn(turnId: String) {
        // Implement retry logic if needed
    }

    override fun onCleared() {
        super.onCleared()
        pipeline?.stop()
        translator?.close()
        bluetoothAudioManager.release()
        delegatingTransport.destroy()
        roomTransport.destroy()
        btManager.destroy()
        diagnostics.stop()
    }
}
