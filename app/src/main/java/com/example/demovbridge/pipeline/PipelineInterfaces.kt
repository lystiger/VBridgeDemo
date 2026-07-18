package com.example.demovbridge.pipeline

import com.example.demovbridge.network.TranslationEvent
import com.example.demovbridge.network.NetworkEvent
import kotlinx.coroutines.flow.Flow

interface SpeechRecognizer {
    suspend fun transcribe(pcm: PcmAudio): String
    fun release()
}

interface SpeechSynthesizer {
    suspend fun synthesize(text: String): SynthesizedAudio
    fun release()
}

interface AudioPlayer {
    suspend fun play(audio: SynthesizedAudio)
    fun stop()
    fun release()
}

sealed interface TransportSendResult {
    object Success : TransportSendResult
    data class Failure(val message: String) : TransportSendResult
}

interface TranslationTransport {
    val events: Flow<NetworkEvent>
    suspend fun send(event: TranslationEvent): TransportSendResult
    fun disconnect()
    fun destroy()
}
