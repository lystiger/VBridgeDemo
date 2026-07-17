package com.example.demovbridge.translation

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

    override suspend fun ensureModels(direction: Direction): Unit = withContext(Dispatchers.IO) {
        getOrCreateTranslator(direction).downloadModelIfNeeded(
            DownloadConditions.Builder().requireWifi().build()
        ).await()
        Unit
    }

    override suspend fun translate(text: String, direction: Direction): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext ""
        getOrCreateTranslator(direction).translate(text).await()
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

    override fun close() {
        translators.values.forEach { it.close() }
        translators.clear()
    }
}
