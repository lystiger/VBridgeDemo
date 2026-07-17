package com.example.demovbridge.translation

import android.os.SystemClock
import com.example.demovbridge.pipeline.Direction
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MlKitTranslator : Translator {
    private val translators = mutableMapOf<Direction, com.google.mlkit.nl.translate.Translator>()

    override suspend fun translate(text: String, direction: Direction): TranslationResult = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext TranslationResult("", 0, "MLKit")
        
        val startTime = SystemClock.elapsedRealtime()
        val translator = getOrCreateTranslator(direction)
        
        // Ensure model is downloaded (basic check)
        translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
        
        val result = translator.translate(text).await()
        val endTime = SystemClock.elapsedRealtime()
        
        TranslationResult(
            text = result,
            latencyMs = endTime - startTime,
            modelName = "MLKit Baseline"
        )
    }

    private fun getOrCreateTranslator(direction: Direction): com.google.mlkit.nl.translate.Translator {
        return translators.getOrPut(direction) {
            val options = TranslatorOptions.Builder()
                .setSourceLanguage(mapLanguage(direction.mlkitSource))
                .setTargetLanguage(mapLanguage(direction.mlkitTarget))
                .build()
            Translation.getClient(options)
        }
    }

    private fun mapLanguage(code: String): String = when (code) {
        "vi" -> TranslateLanguage.VIETNAMESE
        "en" -> TranslateLanguage.ENGLISH
        else -> TranslateLanguage.ENGLISH
    }

    fun close() {
        translators.values.forEach { it.close() }
        translators.clear()
    }
}
