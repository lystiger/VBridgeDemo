package com.example.demovbridge.asr

import android.content.Context
import android.util.Log
import com.example.demovbridge.pipeline.Direction
import com.whispercpp.whisper.WhisperContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class WhisperAsr(private val context: Context, modelPath: String) {
    private var whisperContext: WhisperContext? = null

    init {
        try {
            val modelFile = File(modelPath)
            if (!modelFile.exists()) {
                Log.e("WhisperAsr", "Model not found at $modelPath")
            } else {
                whisperContext = WhisperContext.createContextFromFile(modelPath)
                Log.d("WhisperAsr", "Model loaded successfully from $modelPath")
            }
        } catch (e: Exception) {
            Log.e("WhisperAsr", "Failed to load model: ${e.message}")
        }
    }

    suspend fun transcribe(samples: ShortArray, direction: Direction): String = withContext(Dispatchers.Default) {
        val whisper = whisperContext ?: return@withContext ""
        
        try {
            // Convert PCM16 -> float [-1,1]
            val floatSamples = FloatArray(samples.size) { samples[it] / 32768.0f }
            
            // Whisper expects 16kHz mono
            val lang = direction.asrLang 
            
            return@withContext whisper.transcribeData(floatSamples, language = lang, printTimestamp = false)
        } catch (e: Exception) {
            Log.e("WhisperAsr", "Transcription error: ${e.message}")
            ""
        }
    }

    suspend fun release() {
        whisperContext?.release()
        whisperContext = null
    }

    fun isReady(): Boolean = whisperContext != null
}
