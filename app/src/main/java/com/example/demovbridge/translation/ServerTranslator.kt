package com.example.demovbridge.translation

import android.os.SystemClock
import com.example.demovbridge.BuildConfig
import com.example.demovbridge.pipeline.Direction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException

class ServerTranslator(
    private val baseUrl: String = BuildConfig.VBRIDGE_MT_URL
) : Translator {
    private val client = OkHttpClient()
    private val jsonType = "application/json".toMediaType()

    override suspend fun translate(text: String, direction: Direction): TranslationResult =
        withContext(Dispatchers.IO) {
            if (text.isBlank()) return@withContext TranslationResult("", 0, "phomt-server")
            val t0 = SystemClock.elapsedRealtime()
            val src = direction.asrLang
            val tgt = if (direction == Direction.ViToEn) "en" else "vi"
            val payload = JSONObject().apply {
                put("session_id", "android"); put("text", text)
                put("source_language", src); put("target_language", tgt)
            }.toString().toRequestBody(jsonType)
            val req = Request.Builder().url("$baseUrl/translation").post(payload).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("MT server HTTP ${resp.code}")
                val json = JSONObject(resp.body!!.string())
                TranslationResult(json.getString("translated_text"),
                    SystemClock.elapsedRealtime() - t0, "phomt-server")
            }
        }
}
