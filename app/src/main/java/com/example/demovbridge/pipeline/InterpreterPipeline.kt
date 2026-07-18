package com.example.demovbridge.pipeline

import android.os.SystemClock
import com.example.demovbridge.asr.WhisperAsr
import com.example.demovbridge.audio.AudioCapture
import com.example.demovbridge.audio.AudioPlayback
import com.example.demovbridge.translation.Translator
import com.example.demovbridge.tts.AndroidTts
import com.example.demovbridge.network.NetworkEvent
import com.example.demovbridge.network.VBridgeSocket
import com.example.demovbridge.network.TranslationEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.*

data class PendingAudioTurn(
    val turnId: String,
    val pcm: ShortArray,
    val direction: Direction,
    val speechEndedAtMs: Long
)

class InterpreterPipeline(
    private val capture: AudioCapture,
    private val asr: WhisperAsr,
    private val translator: Translator,
    private val tts: AndroidTts,
    private val playback: AudioPlayback,
    private val network: VBridgeSocket,
    private val roomId: String,
    private val localParticipantId: String,
    private val localParticipantName: String,
    private val diagnostics: PipelineDiagnostics? = null
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    private val _events = MutableSharedFlow<PipelineEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<PipelineEvent> = _events.asSharedFlow()

    private val asrIn = Channel<PendingAudioTurn>(capacity = 4, onBufferOverflow = BufferOverflow.SUSPEND)
    private val mtIn = Channel<Pair<PendingAudioTurn, String>>(capacity = 8, onBufferOverflow = BufferOverflow.SUSPEND)
    private val ttsIn = Channel<TranslationEvent>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    
    @Volatile
    private var isMutedForPlayback = false

    @Volatile
    private var started = false
    
    // Safeguard C: Deduplication Cache Layer
    private val recentEventIds = Collections.synchronizedSet(LinkedHashSet<String>())

    var currentDirection = Direction.ViToEn

    private var captureJob: Job? = null

    @Synchronized
    fun start() {
        if (started) return
        started = true

        // 0. Network Listener -> TTS
        scope.launch {
            network.events.collect { event ->
                if (event is NetworkEvent.TranslationReceived) {
                    val translationEvent = event.event
                    
                    // Safeguard D: Ignore self-produced events
                    if (translationEvent.speakerId == localParticipantId) return@collect

                    // Safeguard C: Deduplication
                    if (recentEventIds.contains(translationEvent.eventId)) return@collect
                    
                    if (recentEventIds.size > 200) {
                        recentEventIds.remove(recentEventIds.firstOrNull())
                    }
                    recentEventIds.add(translationEvent.eventId)

                    val direction = if (translationEvent.sourceLanguage == "vi") Direction.ViToEn else Direction.EnToVi

                    _events.emit(PipelineEvent.Translated(
                        translationEvent.eventId, translationEvent.sourceText, translationEvent.translatedText, 
                        direction, SystemClock.elapsedRealtime(),
                        speakerName = translationEvent.speakerName,
                        isLocal = false
                    ))
                    
                    // Remote translation needs to be spoken locally
                    ttsIn.send(translationEvent)
                }
            }
        }

        // 2. ASR
        scope.launch {
            for (pending in asrIn) {
                try {
                    val startTime = SystemClock.elapsedRealtime()
                    val text = asr.transcribe(pending.pcm, pending.direction)
                    val endTime = SystemClock.elapsedRealtime()
                    
                    diagnostics?.recordAsrPerformance(
                        audioDurationMs = (pending.pcm.size / 16).toLong(),
                        processingTimeMs = endTime - startTime
                    )

                    _events.emit(PipelineEvent.Transcribed(pending.turnId, text, pending.direction, endTime))
                    mtIn.send(pending to text)
                } catch (e: Exception) {
                    _events.emit(PipelineEvent.Failed(pending.turnId, PipelineEvent.Stage.Asr, e.message ?: "ASR Failed", false))
                }
            }
        }

        // 3. Translation -> Network
        scope.launch {
            for ((pending, text) in mtIn) {
                try {
                    val startTime = SystemClock.elapsedRealtime()
                    val result = translator.translate(text, pending.direction)
                    val endTime = SystemClock.elapsedRealtime()
                    
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

                    recentEventIds.add(pending.turnId)
                    if (recentEventIds.size > 200) {
                        recentEventIds.remove(recentEventIds.firstOrNull())
                    }

                    val sent = network.sendTranslation(event)
                    if (!sent) {
                        _events.emit(
                            PipelineEvent.Failed(
                                turnId = pending.turnId,
                                stage = PipelineEvent.Stage.Network,
                                message = "Not connected. Translation was not delivered.",
                                usedFallback = false
                            )
                        )
                        continue
                    }

                    _events.emit(
                        PipelineEvent.Translated(
                            pending.turnId, text, result.text, pending.direction, endTime,
                            speakerName = localParticipantName,
                            isLocal = true
                        )
                    )
                    
                } catch (e: Exception) {
                    _events.emit(PipelineEvent.Failed(pending.turnId, PipelineEvent.Stage.Translation, e.message ?: "MT Failed", false))
                }
            }
        }

        // 4. TTS
        scope.launch {
            for (event in ttsIn) {
                try {
                    val locale = if (event.targetLanguage == "vi") Locale("vi") else Locale.ENGLISH
                    
                    isMutedForPlayback = true
                    _events.emit(
                        PipelineEvent.PlaybackStarted(
                            turnId = event.eventId,
                            tPlaybackStarted = SystemClock.elapsedRealtime(),
                            isLocal = false
                        )
                    )

                    val completion = CompletableDeferred<Unit>()
                    tts.speak(event.translatedText, locale) {
                        completion.complete(Unit)
                    }
                    
                    completion.await()
                    
                    delay(500)
                    isMutedForPlayback = false
                    _events.emit(
                        PipelineEvent.PlaybackCompleted(
                            turnId = event.eventId,
                            tPlaybackCompleted = SystemClock.elapsedRealtime(),
                            isLocal = false
                        )
                    )
                } catch (e: Exception) {
                    _events.emit(PipelineEvent.Failed(event.eventId, PipelineEvent.Stage.Tts, e.message ?: "TTS Failed", false))
                }
            }
        }
    }

    fun startRecording() {
        if (isMutedForPlayback) return
        
        captureJob?.cancel()
        captureJob = scope.launch {
            val audioData = mutableListOf<ShortArray>()
            val turnId = UUID.randomUUID().toString()
            val direction = currentDirection
            
            _events.emit(PipelineEvent.SpeechStarted(turnId, SystemClock.elapsedRealtime()))
            
            try {
                capture.capture().collect { samples ->
                    audioData.add(samples)
                }
            } finally {
                withContext(NonCancellable) {
                    if (audioData.isNotEmpty()) {
                        val pcm = ShortArray(audioData.sumOf { it.size })
                        var offset = 0
                        audioData.forEach {
                            it.copyInto(pcm, offset)
                            offset += it.size
                        }
                        val endTime = SystemClock.elapsedRealtime()
                        _events.emit(PipelineEvent.SpeechEnded(turnId, endTime, pcm))
                        asrIn.send(PendingAudioTurn(turnId, pcm, direction, endTime))
                    } else {
                        // Safeguard: If no audio was captured (e.g. very short tap),
                        // we must still transition out of the Recording state.
                        _events.emit(
                            PipelineEvent.Failed(
                                turnId = turnId,
                                stage = PipelineEvent.Stage.Asr,
                                message = "No audio captured",
                                usedFallback = false
                            )
                        )
                    }
                }
            }
        }
    }

    fun stopRecording() {
        captureJob?.cancel()
        captureJob = null
    }

    fun stop() {
        captureJob?.cancel()
        captureJob = null
        scope.cancel()
        playback.stop()
        started = false
    }
}
