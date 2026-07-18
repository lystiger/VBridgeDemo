package com.example.demovbridge.vad

interface VoiceActivityDetector {
    val isSpeaking: Boolean
    fun process(samples: ShortArray): List<ShortArray>?
    fun flush(): List<ShortArray>?
    fun reset()
    fun release()
}
