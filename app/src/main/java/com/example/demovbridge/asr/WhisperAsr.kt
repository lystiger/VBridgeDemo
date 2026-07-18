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
                android.util.Log.wtf("VBRIDGE_DEBUG", "WhisperAsr: Model not found at $modelPath")
            } else {
                android.util.Log.wtf("VBRIDGE_DEBUG", "WhisperAsr: Calling createContextFromFile for ${modelFile.name}")
                whisperContext = WhisperContext.createContextFromFile(modelPath)
                android.util.Log.wtf("VBRIDGE_DEBUG", "WhisperAsr: createContextFromFile SUCCESS")
            }
        } catch (e: Exception) {
            android.util.Log.wtf("VBRIDGE_DEBUG", "WhisperAsr: FAILED to load model: ${e.message}")
        }
    }

    suspend fun transcribe(samples: ShortArray, direction: Direction): String = withContext(Dispatchers.Default) {
        val whisper = whisperContext ?: return@withContext ""
        
        try {
            if (samples.isEmpty()) {
                android.util.Log.wtf("VBRIDGE_DEBUG", "[ASR] Transcription aborted: Empty audio buffer")
                return@withContext ""
            }

            // Verify audio levels (RMS & Peak)
            var sum = 0.0
            var peak = 0
            for (s in samples) {
                val abs = if (s < 0) -s.toInt() else s.toInt()
                if (abs > peak) peak = abs
                sum += s.toDouble() * s.toDouble()
            }
            val rms = Math.sqrt(sum / samples.size)
            val durationSec = samples.size / 16000.0
            
            android.util.Log.wtf("VBRIDGE_DEBUG", "[ASR] Audio Stats: Samples=${samples.size}, Duration=${String.format("%.2f", durationSec)}s, RMS=${String.format("%.2f", rms)}, Peak=$peak")

            if (rms < 5.0) { // Lowered threshold further
                android.util.Log.wtf("VBRIDGE_DEBUG", "[ASR] Transcription aborted: Audio is too quiet (silence)")
                return@withContext ""
            }

            // Convert PCM16 -> float [-1,1]
            val floatSamples = FloatArray(samples.size) { samples[it] / 32768.0f }
            
            // Whisper expects 16kHz mono
            val lang = direction.asrLang 
            android.util.Log.wtf("VBRIDGE_DEBUG", "[ASR] Starting inference. Lang=$lang")
            
            val result = whisper.transcribeData(floatSamples, language = lang, printTimestamp = false)
            android.util.Log.wtf("VBRIDGE_DEBUG", "[ASR] Raw result: '$result'")
            
            // If result is empty, try English as a fallback if the current language was Vietnamese
            var finalResult = result
            if (finalResult.isBlank() && lang == "vi") {
                android.util.Log.wtf("VBRIDGE_DEBUG", "[ASR] Empty VI result, retrying with EN fallback...")
                finalResult = whisper.transcribeData(floatSamples, language = "en", printTimestamp = false)
                android.util.Log.wtf("VBRIDGE_DEBUG", "[ASR] Fallback result: '$finalResult'")
            }

            return@withContext finalResult
        } catch (e: Exception) {
            android.util.Log.wtf("VBRIDGE_DEBUG", "[ASR] ERROR: ${e.message}")
            ""
        }
    }

    suspend fun release() {
        whisperContext?.release()
        whisperContext = null
    }

    fun isReady(): Boolean = whisperContext != null
}
