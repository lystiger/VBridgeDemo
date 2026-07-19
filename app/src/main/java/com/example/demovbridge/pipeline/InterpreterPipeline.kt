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
import kotlin.math.sqrt
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
    private val glossary: com.example.demovbridge.translation.Glossary? = null,
    private val roomId: String,
    private val localParticipantId: String,
    private val localParticipantName: String,
    private val diagnostics: PipelineDiagnostics? = null,
    private val isOnline: StateFlow<Boolean> = MutableStateFlow(true),
    // Set to false to fall back to the old fixed-direction behavior (use
    // currentDirection to pick asrVi/asrEn instead of auto-detecting).
    private val autoDetectLanguage: Boolean = true,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime
) {
    private var scope: CoroutineScope? = null

    private val _events = MutableSharedFlow<PipelineEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<PipelineEvent> = _events.asSharedFlow()

    private val asrIn = Channel<PendingAudioTurn>(capacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val mtIn = Channel<Pair<PendingAudioTurn, String>>(capacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val ttsIn = Channel<TranslationEvent>(capacity = 32, onBufferOverflow = BufferOverflow.DROP_OLDEST)

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

        // 2. ASR (with optional auto language detection)
        newScope.launch {
            for (pending in asrIn) {
                try {
                    val startTime = elapsedRealtimeMs()

                    // Resolve which transcript + which direction actually applies to this turn.
                    val (rawText, resolvedDirection) = if (autoDetectLanguage) {
                        detectLanguageAndTranscribe(pending.audio)
                    } else {
                        val asr = if (pending.direction == Direction.ViToEn) asrVi else asrEn
                        asr.transcribe(pending.audio) to pending.direction
                    }

                    // Apply Glossary to correct ASR output before translation
                    val text = glossary?.apply(rawText, resolvedDirection) ?: rawText

                    val endTime = elapsedRealtimeMs()

                    if (text.isBlank()) {
                        _events.emit(PipelineEvent.Failed(pending.turnId, PipelineEvent.Stage.Asr, "Empty transcript", false))
                        continue
                    }

                    diagnostics?.recordAsrPerformance(
                        audioDurationMs = (pending.audio.samples.size / 16).toLong(),
                        processingTimeMs = endTime - startTime
                    )

                    // Carry the *resolved* direction downstream so translation and TTS
                    // target the correct language pair, even if it differs from what
                    // was assumed when recording started.
                    val resolvedPending = if (resolvedDirection == pending.direction) {
                        pending
                    } else {
                        pending.copy(direction = resolvedDirection)
                    }

                    _events.emit(PipelineEvent.Transcribed(resolvedPending.turnId, text, resolvedDirection, endTime))
                    mtIn.send(resolvedPending to text)
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
                    if (sendResult is TransportSendResult.Failure && transport.isRelayActive && isOnline.value) {
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

    /**
     * Runs both language models on the same utterance and picks the more
     * plausible transcript. Heuristic-based (no true confidence score exposed
     * by this sherpa-onnx SDK version):
     *   1. Non-empty beats empty.
     *   2. Longer token count generally beats shorter (wrong-language decode
     *      tends to be sparse/garbled).
     *   3. Vietnamese diacritics present in the VI-model output tip the
     *      balance toward Vietnamese when scores are close.
     *
     * Good enough for a hackathon demo; not a substitute for real language ID.
     */
    private suspend fun detectLanguageAndTranscribe(audio: PcmAudio): Pair<String, Direction> {
        // Sherpa recognizers own large native ONNX allocations. Running both
        // languages concurrently caused a large first-inference memory spike
        // on lower-memory phones and could make Android kill the process.
        // Decode sequentially and release each native model as soon as its
        // transcript has been captured. This trades some latency for a stable
        // memory ceiling during offline Bluetooth conversations.
        val viText = try {
            asrVi.transcribe(audio)
        } catch (_: Exception) {
            ""
        } finally {
            asrVi.release()
        }

        val enText = try {
            asrEn.transcribe(audio)
        } catch (_: Exception) {
            ""
        } finally {
            asrEn.release()
        }

        val viScore = scoreTranscript(viText, isVietnameseModel = true)
        val enScore = scoreTranscript(enText, isVietnameseModel = false)

        return if (viScore >= enScore) {
            viText to Direction.ViToEn
        } else {
            enText to Direction.EnToVi
        }
    }

    private fun scoreTranscript(text: String, isVietnameseModel: Boolean): Double {
        if (text.isBlank()) return -1.0

        val words = text.trim().split(Regex("\\s+"))
        val tokenCount = words.size
        
        var s = 0.0

        // 1. Language-Specific Heuristics
        if (isVietnameseModel) {
            // Vietnamese diacritics are a strong indicator, but balanced now
            if (containsVietnameseDiacritics(text)) {
                s += 1.5
            }
            // Check for common Vietnamese particles
            val viParticles = setOf("là", "của", "và", "có", "không", "cho", "được", "tôi", "anh", "chị", "bạn")
            if (words.any { it.lowercase() in viParticles }) {
                s += 1.0
            }
        } else {
            // English function words (expanded list)
            val enParticles = setOf(
                "the", "and", "is", "of", "to", "a", "in", "that", "it", 
                "i", "you", "my", "this", "can", "have", "for", "with", "what"
            )
            if (words.any { it.lowercase() in enParticles }) {
                s += 1.5 // Increased weight for English matches
            }
            
            // Penalty: English model should NOT produce Vietnamese diacritics
            if (containsVietnameseDiacritics(text)) {
                s -= 2.0
            }
        }

        // 2. Repetition Penalty (Detect hallucinations/noise)
        if (tokenCount >= 3) {
            val uniqueTokens = words.map { it.lowercase() }.toSet().size
            val repetitionRatio = uniqueTokens.toDouble() / tokenCount
            if (repetitionRatio < 0.4) {
                s -= 1.5 // Heavy penalty for "the the the" or similar garble
            }
        }

        // 3. Length Normalization & Tie-breaking
        s += tokenCount * 0.1

        // 4. Short-phrase handling
        if (tokenCount <= 1 && text.length < 3) {
            s -= 0.5
        }

        return s
    }

    private fun containsVietnameseDiacritics(text: String): Boolean {
        val diacriticRange = Regex(
            "[àáạảãâầấậẩẫăằắặẳẵèéẹẻẽêềếệểễìíịỉĩòóọỏõôồốộổỗơờớợởỡ" +
                    "ùúụủũưừứựửữỳýỵỷỹđ]",
            RegexOption.IGNORE_CASE
        )
        return diacriticRange.containsMatchIn(text)
    }

    private fun calculateRms(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (sample in samples) {
            sum += sample.toDouble() * sample
        }
        return sqrt(sum / samples.size)
    }

    fun startRecording() {
        if (isMutedForPlayback) return
        if (!_started.value) return

        captureJob?.cancel()
        val currentScope = scope ?: return

        captureJob = currentScope.launch {
            vad.reset()
            var turnId = UUID.randomUUID().toString()
            var speechStartedEmitted = false
            val direction = currentDirection

            try {
                capture.capture().collect { samples ->
                    if (!isActive) return@collect
                    
                    val processed = vad.process(samples)
                    
                    // Real-time UI feedback: Speech Started (Emit once per turnId)
                    if (vad.isSpeaking && !speechStartedEmitted) {
                        _events.emit(PipelineEvent.SpeechStarted(turnId, elapsedRealtimeMs()))
                        speechStartedEmitted = true
                    }

                    if (processed != null) {
                        // VAD detected speech end (silence) -> Automatic trigger
                        val totalSize = processed.sumOf { it.size }
                        val pcm = ShortArray(totalSize)
                        var offset = 0
                        processed.forEach {
                            it.copyInto(pcm, offset)
                            offset += it.size
                        }

                        // Noise Filter: Minimum energy check
                        val rms = calculateRms(pcm)
                        if (totalSize > 1600 && rms > 200) {
                            val endTime = elapsedRealtimeMs()
                            _events.emit(PipelineEvent.SpeechEnded(turnId, endTime, pcm))
                            asrIn.send(PendingAudioTurn(turnId, PcmAudio(pcm), direction, endTime))
                        }
                        
                        // Prepare for next utterance in same capture session
                        turnId = UUID.randomUUID().toString()
                        speechStartedEmitted = false
                    }
                }
            } catch (e: Exception) {
                _events.emit(PipelineEvent.Failed("unknown", PipelineEvent.Stage.Capture, e.message ?: "Capture failed", false))
            } finally {
                withContext(NonCancellable) {
                    val flushed = vad.flush()
                    if (flushed != null) {
                        val turnId = UUID.randomUUID().toString()
                        val direction = currentDirection

                        val totalSize = flushed.sumOf { it.size }
                        val pcm = ShortArray(totalSize)
                        var offset = 0
                        flushed.forEach {
                            it.copyInto(pcm, offset)
                            offset += it.size
                        }

                        // Noise Filter: Minimum energy check
                        val rms = calculateRms(pcm)
                        if (totalSize > 1600 && rms > 200) {
                            val endTime = elapsedRealtimeMs()
                            _events.emit(PipelineEvent.SpeechEnded(turnId, endTime, pcm))
                            asrIn.send(PendingAudioTurn(turnId, PcmAudio(pcm), direction, endTime))
                        }
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
