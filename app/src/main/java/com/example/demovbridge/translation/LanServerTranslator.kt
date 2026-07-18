package com.example.demovbridge.translation

import android.os.SystemClock
import com.example.demovbridge.net.LanFallbackClient
import com.example.demovbridge.pipeline.Direction

class LanServerTranslator(
    private val client: LanFallbackClient,
    private val elapsedRealtimeMs: () -> Long = SystemClock::elapsedRealtime
) : Translator {
    override suspend fun translate(text: String, direction: Direction): TranslationResult {
        val start = elapsedRealtimeMs()
        val body = client.translate(text, direction.mlkitSource, direction.mlkitTarget)
        val out = parseTranslationResponse(body)
        return TranslationResult(
            text = out,
            latencyMs = elapsedRealtimeMs() - start,
            modelName = "LAN"
        )
    }

    override fun close() {}

    companion object {
        private val translationField = Regex(
            """[\"'](?:translation|text)[\"']\s*:\s*\"((?:\\.|[^\"\\])*)\""""
        )

        internal fun parseTranslationResponse(body: String): String {
            if (body.isBlank()) throw IllegalStateException("LAN translate empty/failed")

            val trimmed = body.trim()
            val parsed = if (trimmed.startsWith("{")) {
                val encoded = translationField.find(trimmed)?.groupValues?.get(1)
                    ?: throw IllegalStateException("LAN translate parse failed")
                decodeJsonString(encoded)
            } else {
                trimmed
            }

            return parsed.trim().ifBlank {
                throw IllegalStateException("LAN translate parse failed")
            }
        }

        private fun decodeJsonString(encoded: String): String {
            val decoded = StringBuilder(encoded.length)
            var index = 0
            while (index < encoded.length) {
                val char = encoded[index++]
                if (char != '\\') {
                    decoded.append(char)
                    continue
                }
                if (index >= encoded.length) throw IllegalStateException("LAN translate parse failed")
                when (val escaped = encoded[index++]) {
                    '"', '\\', '/' -> decoded.append(escaped)
                    'b' -> decoded.append('\b')
                    'f' -> decoded.append('\u000C')
                    'n' -> decoded.append('\n')
                    'r' -> decoded.append('\r')
                    't' -> decoded.append('\t')
                    'u' -> {
                        if (index + 4 > encoded.length) {
                            throw IllegalStateException("LAN translate parse failed")
                        }
                        val codePoint = encoded.substring(index, index + 4).toIntOrNull(16)
                            ?: throw IllegalStateException("LAN translate parse failed")
                        decoded.append(codePoint.toChar())
                        index += 4
                    }
                    else -> throw IllegalStateException("LAN translate parse failed")
                }
            }
            return decoded.toString()
        }
    }
}
