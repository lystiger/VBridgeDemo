package com.example.demovbridge.network.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import com.example.demovbridge.network.NetworkEvent
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.PrintWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@SuppressLint("MissingPermission")
class BluetoothConnectionManager(
    private val adapter: BluetoothAdapter?
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var socket: BluetoothSocket? = null
    private var serverSocket: BluetoothServerSocket? = null
    private val sendMutex = Mutex()
    private var writer: PrintWriter? = null
    private var connectionJob: Job? = null
    private var reconnectJob: Job? = null
    private var desiredConnection: DesiredConnection? = null

    private val _events = MutableSharedFlow<NetworkEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<NetworkEvent> = _events.asSharedFlow()

    private val _connectionState = MutableStateFlow<BluetoothConnectionState>(BluetoothConnectionState.Idle)
    val connectionState: StateFlow<BluetoothConnectionState> = _connectionState.asStateFlow()

    private var isRunning = false

    private sealed interface DesiredConnection {
        data object Host : DesiredConnection
        data class Client(val device: BluetoothDevice) : DesiredConnection
    }

    fun startHost() {
        if (adapter == null) {
            scope.launch { _events.emit(NetworkEvent.Error("Bluetooth not supported")) }
            return
        }
        desiredConnection = DesiredConnection.Host
        closeCurrentConnection()
        isRunning = true
        connectionJob = scope.launch {
            try {
                _connectionState.value = BluetoothConnectionState.Waiting
                _events.emit(NetworkEvent.Connecting)
                serverSocket = adapter.listenUsingRfcommWithServiceRecord(
                    VBridgeBluetoothProtocol.SERVICE_NAME,
                    VBridgeBluetoothProtocol.SERVICE_UUID
                )
                val acceptedSocket = serverSocket?.accept()
                serverSocket?.close()
                serverSocket = null
                
                if (acceptedSocket != null) {
                    handleConnectedSocket(acceptedSocket)
                }
            } catch (e: SecurityException) {
                reportError("Bluetooth permission denied", e)
            } catch (e: IOException) {
                if (isRunning) {
                    reportError("Failed to host: ${e.message}", e)
                    scheduleReconnect()
                }
            }
        }
    }

    fun connectToDevice(device: BluetoothDevice) {
        desiredConnection = DesiredConnection.Client(device)
        closeCurrentConnection()
        isRunning = true
        connectionJob = scope.launch {
            try {
                _connectionState.value = BluetoothConnectionState.Connecting
                _events.emit(NetworkEvent.Connecting)
                val clientSocket = device.createRfcommSocketToServiceRecord(VBridgeBluetoothProtocol.SERVICE_UUID)
                adapter?.cancelDiscovery()
                clientSocket.connect()
                handleConnectedSocket(clientSocket)
            } catch (e: SecurityException) {
                reportError("Bluetooth permission denied", e)
            } catch (e: IOException) {
                if (isRunning) {
                    reportError("Failed to connect: ${e.message}", e)
                    scheduleReconnect()
                }
            }
        }
    }

    private suspend fun handleConnectedSocket(connectedSocket: BluetoothSocket) {
        socket = connectedSocket
        writer = PrintWriter(connectedSocket.outputStream, true)
        _connectionState.value = BluetoothConnectionState.Connected
        _events.emit(NetworkEvent.Connected)
        
        try {
            val reader = BufferedReader(InputStreamReader(connectedSocket.inputStream))
            while (isRunning) {
                val line = reader.readLine() ?: break
                val event = BluetoothEventCodec.decode(line)
                if (event != null) {
                    _events.emit(NetworkEvent.TranslationReceived(event))
                } else {
                    _events.emit(NetworkEvent.Error("Received malformed Bluetooth message"))
                }
            }
        } catch (e: IOException) {
            if (isRunning) {
                Log.e("BTManager", "Receive error", e)
                _events.emit(NetworkEvent.Error("Connection lost: ${e.message}"))
            }
        } finally {
            closeSockets()
            if (isRunning) {
                isRunning = false
                _connectionState.value = BluetoothConnectionState.Disconnected
                _events.emit(NetworkEvent.Disconnected)
                scheduleReconnect()
            }
        }
    }

    suspend fun send(data: String): Boolean = sendMutex.withLock {
        val currentSocket = socket ?: return false
        return try {
            val currentWriter = writer ?: return false
            currentWriter.println(data)
            currentWriter.flush()
            val successful = !currentWriter.checkError()
            if (!successful) {
                scope.launch {
                    _events.emit(NetworkEvent.Error("Bluetooth send failed"))
                }
            }
            successful
        } catch (e: Exception) {
            Log.e("BTManager", "Send error", e)
            scope.launch { reportError("Bluetooth send failed: ${e.message}", e) }
            false
        }
    }

    fun disconnect() {
        val wasRunning = isRunning || socket != null || serverSocket != null
        isRunning = false
        connectionJob?.cancel()
        connectionJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        desiredConnection = null
        closeSockets()
        _connectionState.value = BluetoothConnectionState.Disconnected
        if (!wasRunning) return
        scope.launch { _events.emit(NetworkEvent.Disconnected) }
    }

    fun stop(emitDisconnected: Boolean = true) {
        val wasRunning = isRunning || socket != null || serverSocket != null
        isRunning = false
        connectionJob?.cancel()
        connectionJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        desiredConnection = null
        closeSockets()
        _connectionState.value = BluetoothConnectionState.Disconnected
        if (emitDisconnected && wasRunning) scope.launch { _events.emit(NetworkEvent.Disconnected) }
    }

    fun destroy() {
        stop()
        scope.cancel()
    }

    private fun closeSockets() {
        writer?.close()
        writer = null
        try { socket?.close() } catch (e: IOException) { Log.e("BTManager", "Close error", e) }
        socket = null
        try { serverSocket?.close() } catch (e: IOException) { Log.e("BTManager", "Server close error", e) }
        serverSocket = null
    }

    private fun closeCurrentConnection() {
        isRunning = false
        connectionJob?.cancel()
        connectionJob = null
        reconnectJob?.cancel()
        reconnectJob = null
        closeSockets()
    }

    private fun scheduleReconnect() {
        val desired = desiredConnection ?: return
        isRunning = false
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(1_500)
            when (desired) {
                DesiredConnection.Host -> startHost()
                is DesiredConnection.Client -> connectToDevice(desired.device)
            }
        }
    }

    private suspend fun reportError(message: String, cause: Throwable) {
        Log.e("BTManager", message, cause)
        _connectionState.value = BluetoothConnectionState.Error(message)
        _events.emit(NetworkEvent.Error(message))
    }
}
