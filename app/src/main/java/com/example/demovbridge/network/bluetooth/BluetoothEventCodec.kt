package com.example.demovbridge.network.bluetooth

import com.example.demovbridge.network.TranslationEvent
import org.json.JSONObject

object BluetoothEventCodec {
    fun encode(event: TranslationEvent): String {
        val json = JSONObject().apply {
            put("type", "translation")
            put("eventId", event.eventId)
            put("roomId", event.roomId)
            put("speakerId", event.speakerId)
            put("speakerName", event.speakerName)
            put("sourceLanguage", event.sourceLanguage)
            put("targetLanguage", event.targetLanguage)
            put("sourceText", event.sourceText)
            put("translatedText", event.translatedText)
            put("startedAt", event.startedAt)
            put("endedAt", event.endedAt)
            put("latencyMs", event.latencyMs)
            put("confidence", event.confidence ?: JSONObject.NULL)
        }
        return json.toString()
    }

    fun decode(raw: String): TranslationEvent? {
        return try {
            val json = JSONObject(raw)
            val type = json.optString("type")
            if (type != "translation") return null
            
            TranslationEvent(
                eventId = json.getString("eventId"),
                roomId = json.getString("roomId"),
                speakerId = json.getString("speakerId"),
                speakerName = json.getString("speakerName"),
                sourceLanguage = json.getString("sourceLanguage"),
                targetLanguage = json.getString("targetLanguage"),
                sourceText = json.getString("sourceText"),
                translatedText = json.getString("translatedText"),
                startedAt = json.getLong("startedAt"),
                endedAt = json.getLong("endedAt"),
                latencyMs = json.getLong("latencyMs"),
                confidence = if (json.isNull("confidence")) null else json.optDouble("confidence", Double.NaN).let { if (it.isNaN()) null else it.toFloat() }
            )
        } catch (e: Exception) {
            null
        }
    }
}
