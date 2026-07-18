package com.example.demovbridge.pipeline

import com.example.demovbridge.network.NetworkEvent
import com.example.demovbridge.network.TranslationEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class NoOpTransport : TranslationTransport {
    override val events = MutableSharedFlow<NetworkEvent>().asSharedFlow()
    override val isRelayActive get() = false
    override suspend fun send(event: TranslationEvent) = TransportSendResult.Success
    override fun disconnect() {}
    override fun destroy() {}
}
