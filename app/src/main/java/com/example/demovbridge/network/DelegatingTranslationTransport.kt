package com.example.demovbridge.network

import com.example.demovbridge.pipeline.TranslationTransport
import com.example.demovbridge.pipeline.TransportSendResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class DelegatingTranslationTransport : TranslationTransport {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val _activeTransport = MutableStateFlow<TranslationTransport?>(null)
    private var collectionJob: Job? = null
    
    private val _events = MutableSharedFlow<NetworkEvent>(extraBufferCapacity = 64)
    override val events: Flow<NetworkEvent> = _events.asSharedFlow()

    override val isRelayActive: Boolean
        get() = _activeTransport.value?.isRelayActive ?: false

    fun setTransport(transport: TranslationTransport?) {
        collectionJob?.cancel()
        _activeTransport.value = transport
        transport?.let { t ->
            collectionJob = scope.launch {
                t.events.collect { _events.emit(it) }
            }
        }
    }

    override suspend fun send(event: TranslationEvent): TransportSendResult {
        return _activeTransport.value?.send(event) ?: TransportSendResult.Failure("No active transport")
    }

    override fun disconnect() {
        _activeTransport.value?.disconnect()
    }

    override fun destroy() {
        collectionJob?.cancel()
        _activeTransport.value?.destroy()
        scope.cancel()
    }
}
