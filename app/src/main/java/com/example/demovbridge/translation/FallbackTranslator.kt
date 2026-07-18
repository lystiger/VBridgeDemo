package com.example.demovbridge.translation

import com.example.demovbridge.pipeline.Direction

class FallbackTranslator(
    private val primary: Translator,
    private val onDevice: Translator
) : Translator {
    override suspend fun translate(text: String, direction: Direction): TranslationResult =
        runCatching { primary.translate(text, direction) }
            .getOrElse { 
                onDevice.translate(text, direction).copy(modelName = "MLKit (fallback)") 
            }

    override fun close() {
        primary.close()
        onDevice.close()
    }
}
