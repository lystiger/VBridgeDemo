package com.example.demovbridge.vad

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig

class SherpaVad(
    assetManager: AssetManager,
    modelPath: String = "silero_vad.onnx",
    private val sampleRate: Int = 16000,
    minSilenceDuration: Float = 0.5f,
    minSpeechDuration: Float = 0.25f,
    threshold: Float = 0.5f
) {
    private val vad = Vad(
        assetManager,
        VadModelConfig(
            sileroVadModelConfig = SileroVadModelConfig(
                model = modelPath,
                minSilenceDuration = minSilenceDuration,
                minSpeechDuration = minSpeechDuration,
                threshold = threshold,
                windowSize = 512
            ),
            sampleRate = sampleRate,
            numThreads = 1
        )
    )

    private val speechBuffer = mutableListOf<ShortArray>()
    private var isSpeaking = false

    fun process(samples: ShortArray): List<ShortArray>? {
        val floatSamples = FloatArray(samples.size) { samples[it] / 32768.0f }
        vad.acceptWaveform(floatSamples)
        
        if (vad.isSpeechDetected()) {
            if (!isSpeaking) {
                isSpeaking = true
                speechBuffer.clear()
            }
            speechBuffer.add(samples.copyOf())
        } else {
            if (isSpeaking) {
                isSpeaking = false
                val fullUtterance = speechBuffer.toList()
                speechBuffer.clear()
                return fullUtterance
            }
        }
        return null
    }

    fun reset() {
        vad.reset()
        speechBuffer.clear()
        isSpeaking = false
    }
}
