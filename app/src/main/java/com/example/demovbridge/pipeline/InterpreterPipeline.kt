package com.example.demovbridge.pipeline

import android.os.SystemClock
import com.example.demovbridge.asr.SherpaAsr
import com.example.demovbridge.audio.AudioCapture
import com.example.demovbridge.audio.AudioPlayback
import com.example.demovbridge.translation.Translator
import com.example.demovbridge.tts.SherpaTts
import com.example.demovbridge.vad.SherpaVad
import com.example.demovbridge.network.NetworkEvent
import com.example.demovbridge.network.VBridgeSocket
import com.example.demovbridge.network.TranslationEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.util.*

class InterpreterPipeline(
    private val capture: AudioCapture,
    private val vad: SherpaVad,
    private val asrVi: SherpaAsr,
    private val asrEn: SherpaAsr,
    private val translator: Translator,
    private val ttsVi: SherpaTts,
    private val ttsEn: SherpaTts,
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

    private val asrIn = Channel<Pair<String, ShortArray>>(capacity = 4, onBufferOverflow = BufferOverflow.SUSPEND)
    private val mtIn = Channel<Pair<String, Long>>(capacity = 8, onBufferOverflow = BufferOverflow.SUSPEND)
    private val ttsIn = Channel<TranslationEvent>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    // Temporary storage for ASR results to feed into MT with timestamps
    private val asrResults = mutableMapOf<String, String>()

    @Volatile
    private var isMutedForPlayback = false
    
    // Safeguard C: Deduplication Cache Layer
    private val recentEventIds = Collections.synchronizedSet(LinkedHashSet<String>())

    var currentDirection = Direction.ViToEn

    fun start() {
        // 0. Network Listener -> TTS
        scope.launch {
            network.events.collect { event ->
                if (event is NetworkEvent.TranslationReceived) {
                    val translationEvent = event.event
                    
                    // Safeguard C: Deduplication
                    if (recentEventIds.contains(translationEvent.eventId)) return@collect
                    
                    if (recentEventIds.size > 200) {
                        recentEventIds.remove(recentEventIds.firstOrNull())
                    }
                    recentEventIds.add(translationEvent.eventId)

                    val direction = if (translationEvent.sourceLanguage == "vi") Direction.ViToEn else Direction.EnToVi

                    _events.emit(PipelineEvent.Translated(
                        translationEvent.eventId, translationEvent.sourceText, translationEvent.translatedText, 
                        direction, SystemClock.elapsedRealtime()
                    ))
                    
                    // Remote translation needs to be spoken locally
                    ttsIn.send(translationEvent)
                }
            }
        }

        // 1. Capture -> VAD
        scope.launch {
            capture.capture().collect { samples ->
                // Safeguard A: Hardware Capture Mutex (Simplified via flag)
                if (isMutedForPlayback) return@collect
                
                val utterance = vad.process(samples)
                if (utterance != null) {
                    val turnId = UUID.randomUUID().toString()
                    val pcm = ShortArray(utterance.sumOf { it.size })
                    var offset = 0
                    utterance.forEach {
                        it.copyInto(pcm, offset)
                        offset += it.size
                    }
                    
                    _events.emit(PipelineEvent.SpeechEnded(turnId, SystemClock.elapsedRealtime(), pcm))
                    asrIn.send(turnId to pcm)
                }
            }
        }

        // 2. ASR
        scope.launch {
            for ((turnId, pcm) in asrIn) {
                try {
                    val startTime = SystemClock.elapsedRealtime()
                    val asr = if (currentDirection == Direction.ViToEn) asrVi else asrEn
                    val text = asr.transcribe(pcm)
                    val endTime = SystemClock.elapsedRealtime()
                    
                    diagnostics?.recordAsrPerformance(
                        audioDurationMs = (pcm.size / 16).toLong(),
                        processingTimeMs = endTime - startTime
                    )

                    asrResults[turnId] = text
                    _events.emit(PipelineEvent.Transcribed(turnId, text, currentDirection, endTime))
                    mtIn.send(turnId to startTime)
                } catch (e: Exception) {
                    _events.emit(PipelineEvent.Failed(turnId, PipelineEvent.Stage.Asr, e.message ?: "ASR Failed", false))
                }
            }
        }

        // 3. Translation -> Network
        scope.launch {
            for ((turnId, startTime) in mtIn) {
                try {
                    val text = asrResults.remove(turnId) ?: continue
                    val translated = translator.translate(text, currentDirection)
                    val endTime = SystemClock.elapsedRealtime()
                    
                    diagnostics?.recordLatency(endTime - startTime)

                    val event = TranslationEvent(
                        eventId = turnId,
                        roomId = roomId,
                        speakerId = localParticipantId,
                        speakerName = localParticipantName,
                        sourceLanguage = currentDirection.asrLang,
                        targetLanguage = if (currentDirection == Direction.ViToEn) "en" else "vi",
                        sourceText = text,
                        translatedText = translated,
                        startedAt = startTime,
                        endedAt = endTime,
                        latencyMs = endTime - startTime,
                        confidence = 1.0f // Placeholder
                    )

                    // Add to deduplication cache to prevent re-processing if echoed
                    recentEventIds.add(turnId)
                    if (recentEventIds.size > 200) {
                        recentEventIds.remove(recentEventIds.firstOrNull())
                    }

                    _events.emit(PipelineEvent.Translated(turnId, text, translated, currentDirection, endTime))
                    
                    // Safeguard D: Retransmission Invariant (Only local events go to network)
                    network.sendTranslation(event)
                    
                } catch (e: Exception) {
                    _events.emit(PipelineEvent.Failed(turnId, PipelineEvent.Stage.Translation, e.message ?: "MT Failed", false))
                }
            }
        }

        // 4. TTS -> Playback
        scope.launch {
            for (event in ttsIn) {
                try {
                    val targetTts = if (event.targetLanguage == "vi") ttsVi else ttsEn
                    
                    val pcm = targetTts.generate(event.translatedText)
                    _events.emit(PipelineEvent.SpokenReady(event.eventId, pcm, SystemClock.elapsedRealtime()))
                    
                    // Safeguard A: Hardware Capture Mutex During Local TTS Playback
                    isMutedForPlayback = true
                    try {
                        playback.play(pcm)
                    } finally {
                        isMutedForPlayback = false
                    }
                } catch (e: Exception) {
                    _events.emit(PipelineEvent.Failed(event.eventId, PipelineEvent.Stage.Tts, e.message ?: "TTS Failed", false))
                }
            }
        }
    }

    fun stop() {
        scope.cancel()
        playback.stop()
    }
}
