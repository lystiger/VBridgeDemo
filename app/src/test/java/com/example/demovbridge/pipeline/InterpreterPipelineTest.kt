package com.example.demovbridge.pipeline

import com.example.demovbridge.network.NetworkEvent
import com.example.demovbridge.network.TranslationEvent
import com.example.demovbridge.translation.TranslationResult
import com.example.demovbridge.translation.Translator
import com.example.demovbridge.vad.VoiceActivityDetector
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections

class InterpreterPipelineTest {

    private class FakeRecognizer(var text: String = "test") : SpeechRecognizer {
        var transcribeCalled = 0
        override suspend fun transcribe(pcm: PcmAudio): String {
            transcribeCalled++
            return text
        }
        override fun release() {}
    }

    private class FakeTranslator(var result: TranslationResult = TranslationResult("translated", 10, "fake")) : Translator {
        var translateCalled = 0
        override suspend fun translate(text: String, direction: Direction): TranslationResult {
            translateCalled++
            return result
        }
        override fun close() {}
    }

    private class FakeTransport : TranslationTransport {
        private val _events = MutableSharedFlow<NetworkEvent>()
        override val events = _events.asSharedFlow()
        override var isRelayActive: Boolean = true
        val sentEvents = mutableListOf<TranslationEvent>()
        var sendResult: TransportSendResult = TransportSendResult.Success
        val sendCompleted = CompletableDeferred<Unit>()
        
        override suspend fun send(event: TranslationEvent): TransportSendResult {
            sentEvents.add(event)
            sendCompleted.complete(Unit)
            return sendResult
        }
        override fun disconnect() {}
        override fun destroy() {}
        
        suspend fun emit(event: NetworkEvent) = _events.emit(event)
    }

    private class FakeSynthesizer : SpeechSynthesizer {
        override suspend fun synthesize(text: String) = SynthesizedAudio(ShortArray(100), 16000)
        override fun release() {}
    }

    private class FakePlayer : AudioPlayer {
        var playCalled = 0
        override suspend fun play(audio: SynthesizedAudio) {
            playCalled++
        }
        override fun stop() {}
        override fun release() {}
    }

    private class FakeVad : VoiceActivityDetector {
        override var isSpeaking = false
        override fun process(samples: ShortArray): List<ShortArray>? = null
        override fun flush(): List<ShortArray>? = null
        override fun reset() {}
        override fun release() {}
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `local turn processes correctly`() = runTest {
        val recognizer = FakeRecognizer("Hello")
        val translator = FakeTranslator(TranslationResult("Xin chào", 50, "fake"))
        val transport = FakeTransport()
        
        val pipeline = InterpreterPipeline(
            capture = com.example.demovbridge.audio.AudioCapture(),
            vad = FakeVad(),
            asrVi = recognizer,
            asrEn = recognizer,
            translator = translator,
            ttsVi = FakeSynthesizer(),
            ttsEn = FakeSynthesizer(),
            playback = FakePlayer(),
            transport = transport,
            roomId = "room1",
            localParticipantId = "p1",
            localParticipantName = "Name1"
        )
        
        // This is a minimal test to check if it compiles and runs without crashing.
        pipeline.start()
        pipeline.stop()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `when relay is inactive, transport failure does not emit PipelineEvent Failed`() = runTest {
        val recognizer = FakeRecognizer("Hello")
        val translator = FakeTranslator(TranslationResult("Xin chào", 50, "fake"))
        val transport = FakeTransport().apply {
            isRelayActive = false
            sendResult = TransportSendResult.Failure("Disconnected")
        }
        val pipeline = InterpreterPipeline(
            capture = com.example.demovbridge.audio.AudioCapture(),
            vad = FakeVad(),
            asrVi = recognizer,
            asrEn = recognizer,
            translator = translator,
            ttsVi = FakeSynthesizer(),
            ttsEn = FakeSynthesizer(),
            playback = FakePlayer(),
            transport = transport,
            roomId = "room1",
            localParticipantId = "p1",
            localParticipantName = "Name1",
            elapsedRealtimeMs = { 100L }
        )
        val events = Collections.synchronizedList(mutableListOf<PipelineEvent>())
        val translatedEvent = CompletableDeferred<PipelineEvent.Translated>()
        val job = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            pipeline.events.collect { event ->
                events.add(event)
                if (event is PipelineEvent.Translated) translatedEvent.complete(event)
            }
        }

        try {
            pipeline.start()
            pipeline.enqueueAudioTurn(
                PendingAudioTurn(
                    turnId = "turn1",
                    audio = PcmAudio(ShortArray(1600)),
                    direction = Direction.ViToEn,
                    speechEndedAtMs = 50L
                )
            )

            val translated = withContext(Dispatchers.Default) {
                withTimeout(2_000) {
                    transport.sendCompleted.await()
                    translatedEvent.await()
                }
            }

            assertEquals("turn1", translated.turnId)
            assertEquals("Xin chào", translated.translatedText)
            assertTrue(events.none {
                it is PipelineEvent.Failed && it.stage == PipelineEvent.Stage.Network
            })
        } finally {
            pipeline.stop()
            job.cancelAndJoin()
        }
    }
}
