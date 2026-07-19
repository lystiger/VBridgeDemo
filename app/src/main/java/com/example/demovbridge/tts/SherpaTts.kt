package com.example.demovbridge.tts

import android.content.res.AssetManager
import com.example.demovbridge.pipeline.SpeechSynthesizer
import com.example.demovbridge.pipeline.SynthesizedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SherpaTts(
    private val assetManager: AssetManager,
    private val modelPath: String,
    private val tokensPath: String,
    private val dataDir: String = "",
    private val lexiconPath: String = "",
    private val voice: Int = 0
) : SpeechSynthesizer {
    private var tts: OfflineTts? = null
    private val synthesisMutex = Mutex()

    @Synchronized
    private fun getTts(): OfflineTts {
        if (tts == null) {
            tts = OfflineTts(
                assetManager,
                OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = modelPath,
                            lexicon = lexiconPath,
                            tokens = tokensPath,
                            dataDir = dataDir
                        ),
                        // TTS runs when a peer message arrives. A multi-threaded
                        // VITS session creates a large native ONNX workspace and
                        // was killing the receiving process on both test phones.
                        numThreads = 1,
                        debug = false
                    ),
                    maxNumSentences = 1
                )
            )
        }
        return tts!!
    }

    override suspend fun synthesize(text: String): SynthesizedAudio = synthesisMutex.withLock {
        require(text.isNotBlank()) { "Cannot synthesize empty text" }
        val safeText = text.take(MAX_TTS_CHARACTERS)
        try {
            val engine = getTts()
            val audio = engine.generate(safeText, sid = voice)
            val floatSamples: FloatArray = audio.samples
            check(floatSamples.isNotEmpty()) { "TTS generated no audio" }
            val pcm = ShortArray(floatSamples.size) { index ->
                val sample = (floatSamples[index] * 32767.0f).toInt()
                sample.coerceIn(-32768, 32767).toShort()
            }
            SynthesizedAudio(pcm, audio.sampleRate)
        } finally {
            // Do not retain the large native VITS session between peer turns.
            release()
        }
    }

    override fun release() {
        tts?.release()
        tts = null
    }

    private companion object {
        const val MAX_TTS_CHARACTERS = 500
    }
}
