package com.example.demovbridge.translation

import com.example.demovbridge.pipeline.Direction

interface Translator {
    suspend fun translate(text: String, direction: Direction): String
    suspend fun ensureModels(direction: Direction)
    fun close()
}
