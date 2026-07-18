package com.example.demovbridge.asr

import android.content.Context
import com.example.demovbridge.pipeline.Direction
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.runBlocking
import java.io.File

class WhisperAsr(private val context: Context, modelPath: String) {
    private var whisperContext: WhisperContext? = null

    init {
        val modelFile = File(modelPath)
        if (modelFile.exists()) {
            whisperContext = WhisperContext.createContextFromFile(modelPath)
        }
    }

    fun transcribe(samples: ShortArray, direction: Direction): String = runBlocking {
        val whisper = whisperContext ?: return@runBlocking ""
        
        // Convert PCM16 -> float [-1,1]
        val floatSamples = FloatArray(samples.size) { samples[it] / 32768.0f }
        
        // Whisper expects 16kHz mono, which we already have from AudioCapture
        val lang = direction.asrLang // "vi" or "en"
        
        whisper.transcribeData(floatSamples, language = lang, printTimestamp = false)
    }

    fun isReady(): Boolean = whisperContext != null
}
