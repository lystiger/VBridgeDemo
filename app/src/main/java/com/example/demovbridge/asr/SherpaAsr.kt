package com.example.demovbridge.asr

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig

class SherpaAsr(
    assetManager: AssetManager,
    encoderPath: String,
    decoderPath: String,
    joinerPath: String,
    tokensPath: String,
    numThreads: Int = 2
) {
    private val recognizer = OfflineRecognizer(
        assetManager,
        OfflineRecognizerConfig(
            modelConfig = OfflineModelConfig(
                transducer = OfflineTransducerModelConfig(
                    encoder = encoderPath,
                    decoder = decoderPath,
                    joiner = joinerPath
                ),
                tokens = tokensPath,
                numThreads = numThreads,
                debug = false
            )
        )
    )

    fun transcribe(samples: ShortArray): String {
        val floatSamples = FloatArray(samples.size) { samples[it] / 32768.0f }
        val stream = recognizer.createStream()
        stream.acceptWaveform(floatSamples, 16000)
        recognizer.decode(stream)
        return recognizer.getResult(stream).text
    }
}
