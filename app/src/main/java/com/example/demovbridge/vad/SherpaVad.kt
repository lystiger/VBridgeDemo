package com.example.demovbridge.vad

import android.content.res.AssetManager
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import java.util.LinkedList

class SherpaVad(
    private val assetManager: AssetManager,
    private val modelPath: String = "silero_vad.onnx",
    private val sampleRate: Int = 16000,
    private var minSilenceDuration: Float = 0.5f,
    private var minSpeechDuration: Float = 0.25f,
    private var threshold: Float = 0.5f
) : VoiceActivityDetector {
    fun updateParameters(minSilenceDuration: Float, minSpeechDuration: Float, threshold: Float) {
        if (this.minSilenceDuration != minSilenceDuration || 
            this.minSpeechDuration != minSpeechDuration || 
            this.threshold != threshold) {
            
            this.minSilenceDuration = minSilenceDuration
            this.minSpeechDuration = minSpeechDuration
            this.threshold = threshold
            
            vad?.release()
            vad = null
        }
    }
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
    private val preRollBuffer = LinkedList<ShortArray>()
    private val maxPreRollSize = 10 // ~320ms at 512 samples/window and 16kHz
    
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
                
                val currentWindow = windowBuffer.copyOf()
                if (v.isSpeechDetected()) {
                    if (!_isSpeaking) {
                        _isSpeaking = true
                        speechBuffer.clear()
                        // Add pre-roll context
                        speechBuffer.addAll(preRollBuffer)
                        preRollBuffer.clear()
                    }
                    speechBuffer.add(currentWindow)
                } else {
                    if (_isSpeaking) {
                        _isSpeaking = false
                        val fullUtterance = speechBuffer.toList()
                        speechBuffer.clear()
                        preRollBuffer.clear()
                        return fullUtterance
                    } else {
                        // Maintain pre-roll buffer
                        preRollBuffer.addLast(currentWindow)
                        if (preRollBuffer.size > maxPreRollSize) {
                            preRollBuffer.removeAt(0)
                        }
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
            preRollBuffer.clear()
            _isSpeaking = false
            return fullUtterance
        }
        _isSpeaking = false
        speechBuffer.clear()
        preRollBuffer.clear()
        windowBufferOffset = 0
        return null
    }

    override fun release() {
        vad?.release()
        vad = null
        preRollBuffer.clear()
    }

    override fun reset() {
        vad?.reset()
        speechBuffer.clear()
        preRollBuffer.clear()
        _isSpeaking = false
        windowBufferOffset = 0
    }
}
