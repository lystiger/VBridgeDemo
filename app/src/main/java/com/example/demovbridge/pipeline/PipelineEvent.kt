package com.example.demovbridge.pipeline

/** Emitted at every stage transition. All timestamps are SystemClock.elapsedRealtime() ms. */
sealed interface PipelineEvent {
    val turnId: String

    data class SpeechStarted(override val turnId: String, val tStart: Long) : PipelineEvent
    data class SpeechEnded(override val turnId: String, val tEnd: Long, val pcm: ShortArray) : PipelineEvent
    data class Transcribed(
        override val turnId: String, val text: String, val direction: Direction, val tAsrDone: Long,
        val isLocal: Boolean = true
    ) : PipelineEvent
    data class Translated(
        override val turnId: String, val sourceText: String, val translatedText: String,
        val direction: Direction, val tMtDone: Long,
        val speakerName: String? = null, val isLocal: Boolean = true
    ) : PipelineEvent
    data class SpokenReady(
        override val turnId: String,
        val pcm: ShortArray,
        val tTtsDone: Long,
        val isLocal: Boolean = true
    ) : PipelineEvent

    data class PlaybackStarted(
        override val turnId: String,
        val tPlaybackStarted: Long,
        val isLocal: Boolean = false
    ) : PipelineEvent

    data class PlaybackCompleted(
        override val turnId: String,
        val tPlaybackCompleted: Long,
        val isLocal: Boolean = false
    ) : PipelineEvent

    data class Failed(
        override val turnId: String, val stage: Stage, val message: String, val usedFallback: Boolean,
    ) : PipelineEvent

    enum class Stage {
        Capture,
        Vad,
        Asr,
        Translation,
        Network,
        Tts,
        Playback,
        Cancelled
    }
}
