package com.example.demovbridge.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class LanFallbackClient(private val baseUrl: String) {
    private val client = OkHttpClient()

    suspend fun translate(text: String, from: String, to: String): String = withContext(Dispatchers.IO) {
        val json = """{"text": "$text", "from": "$from", "to": "$to"}"""
        val body = json.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$baseUrl/translation")
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Unexpected code $response")
                response.body?.string() ?: ""
            }
        } catch (e: Exception) {
            ""
        }
    }
}
