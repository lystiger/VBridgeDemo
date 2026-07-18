package com.example.demovbridge.translation

import com.example.demovbridge.pipeline.Direction

data class TranslationResult(
    val text: String,
    val latencyMs: Long,
    val modelName: String,
    val confidence: Float? = null
)

interface Translator {
    suspend fun translate(
        text: String,
        direction: Direction
    ): TranslationResult

    fun close()
}
