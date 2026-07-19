package com.example.demovbridge.translation

import android.util.Log
import com.example.demovbridge.pipeline.Direction

class DelegatingTranslator(
    private val onDevice: Translator,
    private val remote: Translator? = null,
    initialUseRemote: Boolean = false
) : Translator {
    @Volatile
    var useRemote: Boolean = initialUseRemote

    override suspend fun translate(text: String, direction: Direction): TranslationResult {
        val remoteTranslator = remote
        return if (useRemote && remoteTranslator != null) {
            runCatching {
                remoteTranslator.translate(text, direction)
            }.getOrElse { e ->
                // Ghi log để theo dõi tần suất fallback khi Remote gặp lỗi[cite: 2]
                Log.e("DelegatingTranslator", "Remote translation failed, falling back to MLKit: ${e.message}")
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