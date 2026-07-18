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
    private val diagnostics: PipelineDiagnostics? = null,
    val isOffline: Boolean = false
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
    
    private val recentEventIds = Collections.synchronizedSet(LinkedHashSet<String>())

    var currentDirection = Direction.ViToEn

    private var captureJob: Job? = null

    @Synchronized
    fun start() {
        if (started) return
        started = true
        android.util.Log.wtf("VBRIDGE_DEBUG", "[PIPELINE] Pipeline started")

        // 1. Network Listener -> TTS
        // ...
        scope.launch {
            network.events.collect { event ->
                if (event is NetworkEvent.TranslationReceived) {
                    val translationEvent = event.event
                    if (translationEvent.speakerId == localParticipantId) return@collect
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
                    
                    ttsIn.send(translationEvent)
                }
            }
        }

        // 2. ASR with Timeout
        scope.launch {
            android.util.Log.wtf("VBRIDGE_DEBUG", "[PIPELINE] ASR consumer loop active")
            for (pending in asrIn) {
                try {
                    android.util.Log.wtf("VBRIDGE_DEBUG", "[PIPELINE] ASR received turn: ${pending.turnId} (${pending.pcm.size} samples)")
                    val startTime = SystemClock.elapsedRealtime()
                    val text = withTimeout(60000) { // Increased to 60s for Small model
                        asr.transcribe(pending.pcm, pending.direction)
                    }
                    val endTime = SystemClock.elapsedRealtime()
                    android.util.Log.wtf("VBRIDGE_DEBUG", "[PIPELINE] ASR finished: '$text' in ${endTime - startTime}ms")
                    
                    diagnostics?.recordAsrPerformance(
                        audioDurationMs = (pending.pcm.size / 16).toLong(),
                        processingTimeMs = endTime - startTime
                    )

                    _events.emit(PipelineEvent.Transcribed(pending.turnId, text, pending.direction, endTime))
                    
                    if (text.isNotBlank()) {
                        mtIn.send(pending to text)
                    } else {
                        android.util.Log.w("InterpreterPipeline", "ASR returned empty string, not sending to MT")
                        _events.emit(PipelineEvent.Failed(pending.turnId, PipelineEvent.Stage.Asr, "Empty transcription", false))
                    }
                } catch (e: Exception) {
                    _events.emit(PipelineEvent.Failed(pending.turnId, PipelineEvent.Stage.Asr, e.message ?: "ASR Timeout/Failed", false))
                }
            }
        }

        // 3. Translation -> Network with Timeout
        scope.launch {
            for ((pending, text) in mtIn) {
                try {
                    val startTime = SystemClock.elapsedRealtime()
                    val result = withTimeout(10000) {
                        translator.translate(text, pending.direction)
                    }
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

                    network.sendTranslation(event)

                    _events.emit(
                        PipelineEvent.Translated(
                            pending.turnId, text, result.text, pending.direction, endTime,
                            speakerName = localParticipantName,
                            isLocal = true
                        )
                    )

                    if (isOffline) {
                        ttsIn.send(event)
                    }
                    
                } catch (e: Exception) {
                    _events.emit(PipelineEvent.Failed(pending.turnId, PipelineEvent.Stage.Translation, e.message ?: "MT Timeout/Failed", false))
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
                    
                    withTimeoutOrNull(20000) {
                        completion.await()
                    }
                    
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
                    isMutedForPlayback = false
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
                        val totalSamples = audioData.sumOf { it.size }
                        val pcm = ShortArray(totalSamples)
                        var offset = 0
                        audioData.forEach {
                            it.copyInto(pcm, offset)
                            offset += it.size
                        }
                        
                        val durationMs = totalSamples / 16
                        android.util.Log.d("InterpreterPipeline", "Audio turn collected: $totalSamples samples (${durationMs}ms)")
                        
                        if (durationMs < 500) {
                            android.util.Log.w("InterpreterPipeline", "Audio too short (${durationMs}ms), skipping ASR")
                            _events.emit(PipelineEvent.Failed(turnId, PipelineEvent.Stage.Asr, "Audio too short", false))
                        } else {
                            val endTime = SystemClock.elapsedRealtime()
                            _events.emit(PipelineEvent.SpeechEnded(turnId, endTime, pcm))
                            asrIn.send(PendingAudioTurn(turnId, pcm, direction, endTime))
                        }
                    } else {
                        android.util.Log.w("InterpreterPipeline", "No audio data collected for turn $turnId")
                        _events.emit(PipelineEvent.Failed(turnId, PipelineEvent.Stage.Asr, "No audio captured", false))
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
