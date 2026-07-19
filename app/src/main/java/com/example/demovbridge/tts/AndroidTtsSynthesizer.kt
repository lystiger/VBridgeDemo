package com.example.demovbridge.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.demovbridge.pipeline.SpeechSynthesizer
import com.example.demovbridge.pipeline.SynthesizedAudio
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Uses the device TTS service rather than loading a large VITS model into the
 * VBridge process. The engine renders a WAV file so the existing pipeline can
 * continue using its transport-agnostic PCM playback stage.
 */
class AndroidTtsSynthesizer(
    context: Context,
    private val locale: Locale
) : SpeechSynthesizer {
    private val appContext = context.applicationContext
    private val initialization = CompletableDeferred<Int>()
    private val mutex = Mutex()
    private val engine = TextToSpeech(appContext) { status -> initialization.complete(status) }

    override suspend fun synthesize(text: String): SynthesizedAudio = mutex.withLock {
        require(text.isNotBlank()) { "Cannot synthesize empty text" }
        check(initialization.await() == TextToSpeech.SUCCESS) { "System TTS initialization failed" }

        val languageResult = engine.setLanguage(locale)
        check(languageResult != TextToSpeech.LANG_MISSING_DATA && languageResult != TextToSpeech.LANG_NOT_SUPPORTED) {
            "System TTS language is unavailable: $locale"
        }

        val utteranceId = UUID.randomUUID().toString()
        val output = File.createTempFile("vbridge-tts-", ".wav", appContext.cacheDir)
        try {
            synthesizeToFile(text.take(MAX_CHARACTERS), output, utteranceId)
            withContext(Dispatchers.IO) { readPcmWave(output) }
        } finally {
            output.delete()
        }
    }

    private suspend fun synthesizeToFile(text: String, output: File, utteranceId: String) {
        suspendCancellableCoroutine<Unit> { continuation ->
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit

                override fun onDone(id: String?) {
                    if (id == utteranceId && continuation.isActive) continuation.resume(Unit)
                }

                @Deprecated("Deprecated by Android")
                override fun onError(id: String?) {
                    onError(id, TextToSpeech.ERROR)
                }

                override fun onError(id: String?, errorCode: Int) {
                    if (id == utteranceId && continuation.isActive) {
                        continuation.resumeWithException(IllegalStateException("System TTS failed: $errorCode"))
                    }
                }
            })

            val result = engine.synthesizeToFile(text, Bundle(), output, utteranceId)
            if (result != TextToSpeech.SUCCESS && continuation.isActive) {
                continuation.resumeWithException(IllegalStateException("System TTS request was rejected"))
            }
            continuation.invokeOnCancellation { engine.stop() }
        }
    }

    private fun readPcmWave(file: File): SynthesizedAudio {
        val bytes = file.readBytes()
        check(bytes.size >= 44 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") {
            "System TTS returned an invalid WAV file"
        }

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 12
        var sampleRate = 0
        var channels = 0
        var bitsPerSample = 0
        var audioFormat = 0
        var dataOffset = -1
        var dataSize = 0

        while (offset + 8 <= bytes.size) {
            val chunkName = String(bytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = buffer.getInt(offset + 4)
            val chunkData = offset + 8
            if (chunkSize < 0 || chunkData + chunkSize > bytes.size) break
            when (chunkName) {
                "fmt " -> if (chunkSize >= 16) {
                    audioFormat = buffer.getShort(chunkData).toInt() and 0xffff
                    channels = buffer.getShort(chunkData + 2).toInt() and 0xffff
                    sampleRate = buffer.getInt(chunkData + 4)
                    bitsPerSample = buffer.getShort(chunkData + 14).toInt() and 0xffff
                }
                "data" -> {
                    dataOffset = chunkData
                    dataSize = chunkSize
                    break
                }
            }
            offset = chunkData + chunkSize + (chunkSize and 1)
        }

        check(audioFormat == 1 && bitsPerSample == 16 && sampleRate > 0 && dataOffset >= 0) {
            "Unsupported system TTS WAV format"
        }
        check(channels == 1 || channels == 2) { "Unsupported TTS channel count: $channels" }

        val frameCount = dataSize / (2 * channels)
        val pcm = ShortArray(frameCount)
        repeat(frameCount) { frame ->
            var mixed = 0
            repeat(channels) { channel ->
                mixed += buffer.getShort(dataOffset + (frame * channels + channel) * 2).toInt()
            }
            pcm[frame] = (mixed / channels).toShort()
        }
        return SynthesizedAudio(pcm, sampleRate)
    }

    override fun release() {
        engine.stop()
        engine.shutdown()
    }

    private companion object {
        const val MAX_CHARACTERS = 500
    }
}
