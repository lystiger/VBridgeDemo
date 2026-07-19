package com.example.demovbridge.network

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
