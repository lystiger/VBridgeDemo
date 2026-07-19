package com.example.demovbridge.network.bluetooth

import com.example.demovbridge.network.NetworkEvent
import com.example.demovbridge.network.TranslationEvent
import com.example.demovbridge.pipeline.TranslationTransport
import com.example.demovbridge.pipeline.TransportSendResult
import kotlinx.coroutines.flow.Flow

class BluetoothTranslationTransport(
    private val connectionManager: BluetoothConnectionManager
) : TranslationTransport {

    override val events: Flow<NetworkEvent> = connectionManager.events

    override val isRelayActive: Boolean
        get() = connectionManager.connectionState.value is BluetoothConnectionState.Connected

    override suspend fun send(event: TranslationEvent): TransportSendResult {
        val json = BluetoothEventCodec.encode(event)
        val success = connectionManager.send(json)
        return if (success) {
            TransportSendResult.Success
        } else {
            TransportSendResult.Failure("Bluetooth send failed")
        }
    }

    override fun disconnect() {
        connectionManager.disconnect()
    }

    override fun destroy() {
        connectionManager.destroy()
    }
}
