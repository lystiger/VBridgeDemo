package com.example.demovbridge.tts

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsConfig

class SherpaTts(
    assetManager: AssetManager,
    modelPath: String,
    tokensPath: String,
    dataDir: String = "",
    lexiconPath: String = "",
    private val voice: Int = 0
) {
    private val tts = OfflineTts(
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

    fun generate(text: String): ShortArray {
        val audio = tts.generate(text, sid = voice)
        val floatSamples: FloatArray = audio.samples
        return ShortArray(floatSamples.size) { index ->
            val sample = (floatSamples[index] * 32767.0f).toInt()
            sample.coerceIn(-32768, 32767).toShort()
        }
    }
}
