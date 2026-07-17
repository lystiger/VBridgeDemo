package com.example.demovbridge.pipeline

import android.os.SystemClock
import com.example.demovbridge.asr.SherpaAsr
import com.example.demovbridge.audio.AudioCapture
import com.example.demovbridge.audio.AudioPlayback
import com.example.demovbridge.translation.Translator
import com.example.demovbridge.tts.SherpaTts
import com.example.demovbridge.vad.SherpaVad
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
    private val playback: AudioPlayback
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    private val _events = MutableSharedFlow<PipelineEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<PipelineEvent> = _events.asSharedFlow()

    private val asrIn = Channel<Pair<String, ShortArray>>(capacity = 4, onBufferOverflow = BufferOverflow.SUSPEND)
    private val mtIn = Channel<Pair<String, String>>(capacity = 8, onBufferOverflow = BufferOverflow.SUSPEND)
    private val ttsIn = Channel<Pair<String, String>>(capacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    var currentDirection = Direction.ViToEn

    fun start() {
        // 1. Capture -> VAD
        scope.launch {
            capture.capture().collect { samples ->
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
                    val asr = if (currentDirection == Direction.ViToEn) asrVi else asrEn
                    val text = asr.transcribe(pcm)
                    _events.emit(PipelineEvent.Transcribed(turnId, text, currentDirection, SystemClock.elapsedRealtime()))
                    mtIn.send(turnId to text)
                } catch (e: Exception) {
                    _events.emit(PipelineEvent.Failed(turnId, PipelineEvent.Stage.Asr, e.message ?: "ASR Failed", false))
                }
            }
        }

        // 3. Translation
        scope.launch {
            for ((turnId, text) in mtIn) {
                try {
                    val translated = translator.translate(text, currentDirection)
                    _events.emit(PipelineEvent.Translated(turnId, text, translated, currentDirection, SystemClock.elapsedRealtime()))
                    ttsIn.send(turnId to translated)
                } catch (e: Exception) {
                    _events.emit(PipelineEvent.Failed(turnId, PipelineEvent.Stage.Translation, e.message ?: "MT Failed", false))
                }
            }
        }

        // 4. TTS -> Playback
        scope.launch {
            for ((turnId, text) in ttsIn) {
                try {
                    // TTS target is the opposite of source
                    val tts = if (currentDirection == Direction.ViToEn) ttsEn else ttsVi
                    val pcm = tts.generate(text)
                    _events.emit(PipelineEvent.SpokenReady(turnId, pcm, SystemClock.elapsedRealtime()))
                    playback.play(pcm)
                } catch (e: Exception) {
                    _events.emit(PipelineEvent.Failed(turnId, PipelineEvent.Stage.Tts, e.message ?: "TTS Failed", false))
                }
            }
        }
    }

    fun stop() {
        scope.cancel()
        playback.stop()
    }
}
