package com.example.demovbridge.translation

import com.example.demovbridge.pipeline.Direction

class DelegatingTranslator(
    private val onDevice: Translator,
    private val remote: Translator? = null
) : Translator {
    @Volatile
    var useRemote: Boolean = false

    override suspend fun translate(text: String, direction: Direction): TranslationResult {
        val remoteTranslator = remote
        return if (useRemote && remoteTranslator != null) {
            runCatching { remoteTranslator.translate(text, direction) }
                .getOrElse {
                    onDevice.translate(text, direction).copy(modelName = "MLKit (fallback)")
                }
        } else {
            onDevice.translate(text, direction)
        }
    }

    override fun close() {
        remote?.close()
        onDevice.close()
    }
}
