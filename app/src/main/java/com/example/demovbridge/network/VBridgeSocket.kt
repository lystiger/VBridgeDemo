package com.example.demovbridge.network

import com.example.demovbridge.BuildConfig
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
    private val serverUrl: String = BuildConfig.VBRIDGE_RELAY_URL
) {
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .build()

    private var socket: WebSocket? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var currentRoomId: String? = null
    private var reconnectJob: Job? = null
    private var reconnectDelay = 1000L

    private val _events = MutableSharedFlow<NetworkEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<NetworkEvent> = _events.asSharedFlow()

    val isConnected: Boolean get() = socket != null

    fun connect(roomId: String) {
        validateServerUrl()?.let { message ->
            scope.launch { _events.emit(NetworkEvent.Error(message)) }
            return
        }

        currentRoomId = roomId
        reconnectJob?.cancel()
        val request = Request.Builder().url("$serverUrl/room/$roomId").build()
        
        scope.launch { _events.emit(NetworkEvent.Connecting) }
        
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("VBridgeSocket", "Connected to room $roomId")
                reconnectDelay = 1000L
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
                            confidence = json.optDouble("confidence").let { if (it.isNaN()) null else it.toFloat() }
                        )
                        
                        // Validate roomId
                        if (event.roomId == currentRoomId) {
                            scope.launch {
                                _events.emit(NetworkEvent.TranslationReceived(event))
                            }
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
                attemptReconnect()
            }
        })
    }

    private fun attemptReconnect() {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(reconnectDelay)
            reconnectDelay = (reconnectDelay * 2).coerceAtMost(30000L)
            currentRoomId?.let { connect(it) }
        }
    }

    private fun validateServerUrl(): String? {
        if (serverUrl.contains("REPLACE_WITH_REAL_RELAY")) {
            return "Relay URL is not configured"
        }

        if (!serverUrl.startsWith("ws://") && !serverUrl.startsWith("wss://")) {
            return "Relay URL must begin with ws:// or wss://"
        }

        return null
    }

    fun sendTranslation(event: TranslationEvent): Boolean {
        val activeSocket = socket ?: return false

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
        return activeSocket.send(json.toString())
    }

    fun disconnect() {
        currentRoomId = null
        reconnectJob?.cancel()
        reconnectJob = null
        socket?.close(1000, "Leaving room")
        socket = null

        scope.launch {
            _events.emit(NetworkEvent.Disconnected)
        }
    }

    fun destroy() {
        currentRoomId = null
        reconnectJob?.cancel()
        reconnectJob = null
        socket?.cancel()
        socket = null
        scope.cancel()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
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
    object Connecting : NetworkEvent
    object Connected : NetworkEvent
    object Disconnected : NetworkEvent
    data class TranslationReceived(val event: TranslationEvent) : NetworkEvent
    data class Error(val message: String) : NetworkEvent
}
