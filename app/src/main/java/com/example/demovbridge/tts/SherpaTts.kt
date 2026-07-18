package com.example.demovbridge.tts

import android.content.res.AssetManager
import com.example.demovbridge.pipeline.SpeechSynthesizer
import com.example.demovbridge.pipeline.SynthesizedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsConfig

class SherpaTts(
    private val assetManager: AssetManager,
    private val modelPath: String,
    private val tokensPath: String,
    private val dataDir: String = "",
    private val lexiconPath: String = "",
    private val voice: Int = 0
) : SpeechSynthesizer {
    private var tts: OfflineTts? = null

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
                        numThreads = 2,
                        debug = false
                    )
                )
            )
        }
        return tts!!
    }

    override suspend fun synthesize(text: String): SynthesizedAudio {
        val t = getTts()
        val audio = t.generate(text, sid = voice)
        val floatSamples: FloatArray = audio.samples
        val pcm = ShortArray(floatSamples.size) { index ->
            val sample = (floatSamples[index] * 32767.0f).toInt()
            sample.coerceIn(-32768, 32767).toShort()
        }
        return SynthesizedAudio(pcm, audio.sampleRate)
    }

    override fun release() {
        tts?.release()
        tts = null
    }
}
