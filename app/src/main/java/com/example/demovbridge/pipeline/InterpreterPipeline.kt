package com.example.demovbridge.pipeline

import android.os.SystemClock
import com.example.demovbridge.audio.AudioCapture
import com.example.demovbridge.network.NetworkEvent
import com.example.demovbridge.network.TranslationEvent
import com.example.demovbridge.utils.LruEventCache
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.*
import java.util.concurrent.atomic.AtomicReference

data class PendingAudioTurn(
    val turnId: String,
    val audio: PcmAudio,
    val direction: Direction,
    val speechEndedAtMs: Long
)

class InterpreterPipeline(
    private val capture: AudioCapture,
    private val vad: com.example.demovbridge.vad.VoiceActivityDetector,
    private val asrVi: SpeechRecognizer,
    private val asrEn: SpeechRecognizer,
    private val translator: com.example.demovbridge.translation.Translator,
    private val ttsVi: SpeechSynthesizer,
    private val ttsEn: SpeechSynthesizer,
    private val playback: AudioPlayer,
    private val transport: TranslationTransport,
    private val roomId: String,
    private val localParticipantId: String,
    private val localParticipantName: String,
    private val diagnostics: PipelineDiagnostics? = null,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime
) {
    private var scope: CoroutineScope? = null
    
    private val _events = MutableSharedFlow<PipelineEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<PipelineEvent> = _events.asSharedFlow()

    private val asrIn = Channel<PendingAudioTurn>(capacity = 4, onBufferOverflow = BufferOverflow.SUSPEND)
    private val mtIn = Channel<Pair<PendingAudioTurn, String>>(capacity = 8, onBufferOverflow = BufferOverflow.SUSPEND)
    private val ttsIn = Channel<TranslationEvent>(capacity = 8, onBufferOverflow = BufferOverflow.SUSPEND) // Changed from DROP_OLDEST
    
    @Volatile
    private var isMutedForPlayback = false

    private val _started = MutableStateFlow(false)
    val started: StateFlow<Boolean> = _started.asStateFlow()
    
    private val recentEventIds = LruEventCache(200)

    private val _currentDirection = AtomicReference(Direction.ViToEn)
    var currentDirection: Direction
        get() = _currentDirection.get()
        set(value) = _currentDirection.set(value)

    private var captureJob: Job? = null

    fun start() {
        if (_started.value) return
        _started.value = true

        val newScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope = newScope

        // 1. Network Listener -> TTS
        newScope.launch {
            transport.events.collect { event ->
                if (event is NetworkEvent.TranslationReceived) {
                    val translationEvent = event.event
                    
                    if (translationEvent.speakerId == localParticipantId) return@collect
                    if (translationEvent.roomId != roomId) return@collect
                    if (!recentEventIds.markIfNew(translationEvent.eventId)) return@collect

                    val direction = if (translationEvent.sourceLanguage == "vi") Direction.ViToEn else Direction.EnToVi

                    _events.emit(PipelineEvent.Translated(
                        translationEvent.eventId, translationEvent.sourceText, translationEvent.translatedText, 
                        direction, elapsedRealtimeMs(),
                        speakerName = translationEvent.speakerName,
                        isLocal = false
                    ))
                    
                    try {
                        ttsIn.send(translationEvent)
                    } catch (e: Exception) {
                        _events.emit(PipelineEvent.Failed(translationEvent.eventId, PipelineEvent.Stage.Tts, "Queue full", false))
                    }
                }
            }
        }

        // 2. ASR
        newScope.launch {
            for (pending in asrIn) {
                try {
                    val startTime = elapsedRealtimeMs()
                    val asr = if (pending.direction == Direction.ViToEn) asrVi else asrEn
                    val text = asr.transcribe(pending.audio)
                    val endTime = elapsedRealtimeMs()
                    
                    if (text.isBlank()) {
                        _events.emit(PipelineEvent.Failed(pending.turnId, PipelineEvent.Stage.Asr, "Empty transcript", false))
                        continue
                    }

                    diagnostics?.recordAsrPerformance(
                        audioDurationMs = (pending.audio.samples.size / 16).toLong(),
                        processingTimeMs = endTime - startTime
                    )

                    _events.emit(PipelineEvent.Transcribed(pending.turnId, text, pending.direction, endTime))
                    mtIn.send(pending to text)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _events.emit(PipelineEvent.Failed(pending.turnId, PipelineEvent.Stage.Asr, e.message ?: "ASR Failed", false))
                }
            }
        }

        // 3. Translation -> Network
        newScope.launch {
            for ((pending, text) in mtIn) {
                try {
                    val startTime = elapsedRealtimeMs()
                    val result = translator.translate(text, pending.direction)
                    val endTime = elapsedRealtimeMs()
                    
                    diagnostics?.recordLatency(result.latencyMs)

                    val event = TranslationEvent(
                        eventId = pending.turnId,
                        roomId = roomId,
                        speakerId = localParticipantId,
                        speakerName = localParticipantName,
                        sourceLanguage = pending.direction.asrLang,
                        targetLanguage = if (pending.direction == Direction.ViToEn) "en" else "vi",
                        sourceText = text,
                        translatedText = result.text,
                        startedAt = pending.speechEndedAtMs,
                        endedAt = endTime,
                        latencyMs = result.latencyMs,
                        confidence = result.confidence
                    )

                    recentEventIds.markIfNew(pending.turnId)

                    _events.emit(
                        PipelineEvent.Translated(
                            pending.turnId, text, result.text, pending.direction, endTime,
                            speakerName = localParticipantName,
                            isLocal = true
                        )
                    )

                    val sendResult = transport.send(event)
                    if (sendResult is TransportSendResult.Failure && transport.isRelayActive) {
                        _events.emit(
                            PipelineEvent.Failed(
                                turnId = pending.turnId,
                                stage = PipelineEvent.Stage.Network,
                                message = sendResult.message,
                                usedFallback = false
                            )
                        )
                    }
                    
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _events.emit(PipelineEvent.Failed(pending.turnId, PipelineEvent.Stage.Translation, e.message ?: "MT Failed", false))
                }
            }
        }

        // 4. TTS -> Playback
        newScope.launch {
            for (event in ttsIn) {
                try {
                    val targetTts = if (event.targetLanguage == "vi") ttsVi else ttsEn
                    
                    val audio = targetTts.synthesize(event.translatedText)
                    _events.emit(
                        PipelineEvent.SpokenReady(
                            turnId = event.eventId,
                            pcm = audio.pcm,
                            tTtsDone = elapsedRealtimeMs(),
                            isLocal = false
                        )
                    )
                    
                    isMutedForPlayback = true
                    try {
                        _events.emit(
                            PipelineEvent.PlaybackStarted(
                                turnId = event.eventId,
                                tPlaybackStarted = elapsedRealtimeMs(),
                                isLocal = false
                            )
                        )
                        playback.play(audio)
                    } finally {
                        isMutedForPlayback = false
                        _events.emit(
                            PipelineEvent.PlaybackCompleted(
                                turnId = event.eventId,
                                tPlaybackCompleted = elapsedRealtimeMs(),
                                isLocal = false
                            )
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _events.emit(PipelineEvent.Failed(event.eventId, PipelineEvent.Stage.Tts, e.message ?: "TTS Failed", false))
                }
            }
        }
    }

    fun startRecording() {
        if (isMutedForPlayback) return
        if (!_started.value) return
        
        captureJob?.cancel()
        val currentScope = scope ?: return
        
        captureJob = currentScope.launch {
            val audioData = mutableListOf<ShortArray>()
            val turnId = UUID.randomUUID().toString()
            val direction = currentDirection
            
            _events.emit(PipelineEvent.SpeechStarted(turnId, elapsedRealtimeMs()))
            
            vad.reset()

            try {
                capture.capture().collect { samples ->
                    vad.process(samples)?.let { utterance ->
                        audioData.addAll(utterance)
                    }
                }
            } catch (e: Exception) {
                 _events.emit(PipelineEvent.Failed(turnId, PipelineEvent.Stage.Capture, e.message ?: "Capture failed", false))
                 return@launch
            } finally {
                withContext(NonCancellable) {
                    vad.flush()?.let { audioData.addAll(it) }
                    
                    if (audioData.isNotEmpty()) {
                        val totalSize = audioData.sumOf { it.size }
                        val pcm = ShortArray(totalSize)
                        var offset = 0
                        audioData.forEach {
                            it.copyInto(pcm, offset)
                            offset += it.size
                        }
                        val endTime = elapsedRealtimeMs()
                        _events.emit(PipelineEvent.SpeechEnded(turnId, endTime, pcm))
                        
                        asrIn.send(PendingAudioTurn(turnId, PcmAudio(pcm), direction, endTime))
                    } else {
                        _events.emit(PipelineEvent.Failed(turnId, PipelineEvent.Stage.Asr, "No speech detected", false))
                    }
                }
            }
        }
    }

    fun stopRecording() {
        captureJob?.cancel()
        captureJob = null
    }

    internal suspend fun enqueueAudioTurn(pending: PendingAudioTurn) {
        check(_started.value) { "Pipeline must be started before enqueueing audio" }
        asrIn.send(pending)
    }

    fun stop() {
        captureJob?.cancel()
        captureJob = null
        scope?.cancel()
        scope = null
        playback.stop()
        playback.release()
        vad.release()
        asrVi.release()
        asrEn.release()
        ttsVi.release()
        ttsEn.release()
        _started.value = false
    }
}
