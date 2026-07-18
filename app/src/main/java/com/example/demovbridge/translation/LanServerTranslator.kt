package com.example.demovbridge.translation

import android.os.SystemClock
import com.example.demovbridge.net.LanFallbackClient
import com.example.demovbridge.pipeline.Direction

class LanServerTranslator(private val client: LanFallbackClient) : Translator {
    override suspend fun translate(text: String, direction: Direction): TranslationResult {
        val start = SystemClock.elapsedRealtime()
        val out = client.translate(text, direction.mlkitSource, direction.mlkitTarget)
        if (out.isBlank()) throw IllegalStateException("LAN translate empty/failed")
        return TranslationResult(text = out, latencyMs = SystemClock.elapsedRealtime() - start, modelName = "LAN")
    }
    override fun close() {}
}
