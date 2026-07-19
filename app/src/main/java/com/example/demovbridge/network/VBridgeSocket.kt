package com.example.demovbridge.network

import com.example.demovbridge.BuildConfig
import android.util.Log
import com.example.demovbridge.pipeline.Direction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.example.demovbridge.network.bluetooth.BluetoothEventCodec
import okhttp3.*
import org.json.JSONObject
import java.util.concurrent.TimeUnit

import com.example.demovbridge.pipeline.TranslationTransport
import com.example.demovbridge.pipeline.TransportSendResult

class VBridgeSocket(
    private val serverUrl: String = BuildConfig.VBRIDGE_RELAY_URL
) : TranslationTransport {
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
    override val events: SharedFlow<NetworkEvent> = _events.asSharedFlow()

    override val isRelayActive: Boolean
        get() = socket != null && currentRoomId != null

    fun connect(roomId: String) {
        validateServerUrl()?.let { message ->
            scope.launch { _events.emit(NetworkEvent.Error(message)) }
            return
        }

        currentRoomId = roomId
        reconnectJob?.cancel()
        val normalizedBase = serverUrl.removeSuffix("/")
        val request = Request.Builder().url("$normalizedBase/room/$roomId").build()
        
        scope.launch { _events.emit(NetworkEvent.Connecting) }
        
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("VBridgeSocket", "Connected to room $roomId")
                reconnectDelay = 1000L
                scope.launch { _events.emit(NetworkEvent.Connected) }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val event = BluetoothEventCodec.decode(text)
                if (event != null && event.roomId == currentRoomId) {
                    scope.launch {
                        _events.emit(NetworkEvent.TranslationReceived(event))
                    }
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

    override suspend fun send(event: TranslationEvent): TransportSendResult {
        val activeSocket = socket ?: return TransportSendResult.Failure("Not connected")
        val json = BluetoothEventCodec.encode(event)
        val sent = activeSocket.send(json)
        return if (sent) TransportSendResult.Success else TransportSendResult.Failure("WebSocket send failed")
    }

    override fun disconnect() {
        currentRoomId = null
        reconnectJob?.cancel()
        reconnectJob = null
        socket?.close(1000, "Leaving room")
        socket = null

        scope.launch {
            _events.emit(NetworkEvent.Disconnected)
        }
    }

    override fun destroy() {
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
