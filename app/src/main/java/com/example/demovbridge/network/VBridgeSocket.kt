package com.example.demovbridge.network

import android.util.Log
import com.example.demovbridge.pipeline.Direction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class VBridgeSocket(
    private val serverUrl: String = "ws://vbridge-relay.herokuapp.com" // Placeholder
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private var socket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _events = MutableSharedFlow<NetworkEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<NetworkEvent> = _events.asSharedFlow()

    fun connect(roomId: String) {
        val request = Request.Builder().url("$serverUrl/room/$roomId").build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("VBridgeSocket", "Connected to room $roomId")
                scope.launch { _events.emit(NetworkEvent.Connected) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val json = JSONObject(text)
                    val type = json.optString("type")
                    if (type == "translation") {
                        val event = TranslationEvent(
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
                            confidence = json.optDouble("confidence").toFloat()
                        )
                        
                        scope.launch {
                            _events.emit(NetworkEvent.TranslationReceived(event))
                        }
                    }
                } catch (e: Exception) {
                    Log.e("VBridgeSocket", "Parse error: ${e.message}")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch { _events.emit(NetworkEvent.Disconnected) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("VBridgeSocket", "Failure: ${t.message}")
                scope.launch { _events.emit(NetworkEvent.Error(t.message ?: "Unknown error")) }
            }
        })
    }

    fun sendTranslation(event: TranslationEvent) {
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
            put("confidence", event.confidence ?: 0.0)
        }
        socket?.send(json.toString())
    }

    fun disconnect() {
        socket?.close(1000, "App closed")
        scope.cancel()
    }
}

data class TranslationEvent(
    val eventId: String,
    val roomId: String,
    val speakerId: String,
    val speakerName: String,
    val sourceLanguage: String,
    val targetLanguage: String,
    val sourceText: String,
    val translatedText: String,
    val startedAt: Long,
    val endedAt: Long,
    val latencyMs: Long,
    val confidence: Float?
)

sealed interface NetworkEvent {
    object Connected : NetworkEvent
    object Disconnected : NetworkEvent
    data class TranslationReceived(val event: TranslationEvent) : NetworkEvent
    data class Error(val message: String) : NetworkEvent
}
