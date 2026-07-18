package com.example.demovbridge.vad

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig

class SherpaVad(
    private val assetManager: AssetManager,
    private val modelPath: String = "silero_vad.onnx",
    private val sampleRate: Int = 16000,
    private val minSilenceDuration: Float = 0.5f,
    private val minSpeechDuration: Float = 0.25f,
    private val threshold: Float = 0.5f
) : VoiceActivityDetector {
    private var vad: Vad? = null

    @Synchronized
    private fun getVad(): Vad {
        if (vad == null) {
            vad = Vad(
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
        }
        return vad!!
    }

    private val speechBuffer = mutableListOf<ShortArray>()
    override val isSpeaking: Boolean
        get() = _isSpeaking
    private var _isSpeaking = false
    private val windowBuffer = ShortArray(512)
    private var windowBufferOffset = 0

    override fun process(samples: ShortArray): List<ShortArray>? {
        var inputOffset = 0
        val v = getVad()
        while (inputOffset < samples.size) {
            val toCopy = minOf(512 - windowBufferOffset, samples.size - inputOffset)
            System.arraycopy(samples, inputOffset, windowBuffer, windowBufferOffset, toCopy)
            windowBufferOffset += toCopy
            inputOffset += toCopy

            if (windowBufferOffset == 512) {
                val floatSamples = FloatArray(512) { windowBuffer[it] / 32768.0f }
                v.acceptWaveform(floatSamples)
                
                if (v.isSpeechDetected()) {
                    if (!_isSpeaking) {
                        _isSpeaking = true
                        speechBuffer.clear()
                    }
                    speechBuffer.add(windowBuffer.copyOf())
                } else {
                    if (_isSpeaking) {
                        _isSpeaking = false
                        val fullUtterance = speechBuffer.toList()
                        speechBuffer.clear()
                        return fullUtterance
                    }
                }
                windowBufferOffset = 0
            }
        }
        return null
    }

    override fun flush(): List<ShortArray>? {
        if (_isSpeaking && speechBuffer.isNotEmpty()) {
            val fullUtterance = speechBuffer.toList()
            speechBuffer.clear()
            _isSpeaking = false
            return fullUtterance
        }
        if (_isSpeaking && windowBufferOffset > 0) {
             val lastChunk = windowBuffer.copyOf(windowBufferOffset)
             val fullUtterance = speechBuffer.toMutableList().apply { add(lastChunk) }
             speechBuffer.clear()
             _isSpeaking = false
             windowBufferOffset = 0
             return fullUtterance
        }
        return null
    }

    override fun release() {
        vad?.release()
        vad = null
    }

    override fun reset() {
        vad?.reset()
        speechBuffer.clear()
        _isSpeaking = false
        windowBufferOffset = 0
    }
}
