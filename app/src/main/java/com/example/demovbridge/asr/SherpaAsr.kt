package com.example.demovbridge.asr

import android.content.res.AssetManager
import com.example.demovbridge.pipeline.PcmAudio
import com.example.demovbridge.pipeline.SpeechRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig

class SherpaAsr(
    private val assetManager: AssetManager,
    private val encoderPath: String,
    private val decoderPath: String,
    private val joinerPath: String,
    private val tokensPath: String,
    private val numThreads: Int = 2
) : SpeechRecognizer {
    private var recognizer: OfflineRecognizer? = null

    @Synchronized
    private fun getRecognizer(): OfflineRecognizer {
        if (recognizer == null) {
            recognizer = OfflineRecognizer(
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
        }
        return recognizer!!
    }

    override suspend fun transcribe(pcm: PcmAudio): String {
        val samples = pcm.samples
        val floatSamples = FloatArray(samples.size) { samples[it] / 32768.0f }
        val r = getRecognizer()
        val stream = r.createStream()
        stream.acceptWaveform(floatSamples, pcm.sampleRate)
        r.decode(stream)
        val result = r.getResult(stream).text
        stream.release()
        return result
    }

    override fun release() {
        recognizer?.release()
        recognizer = null
    }
}
