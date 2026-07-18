package com.example.demovbridge.benchmark

import com.example.demovbridge.pipeline.PipelineEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

data class TurnLatency(
    val turnId: String,
    val captureMs: Long = 0,
    val asrMs: Long = 0,
    val mtMs: Long = 0,
    val ttsMs: Long = 0,
    val e2eMs: Long = 0
)

class LatencyTracer {
    private val turnData = ConcurrentHashMap<String, MutableMap<String, Long>>()
    private val _latencies = MutableStateFlow<List<TurnLatency>>(emptyList())
    val latencies: StateFlow<List<TurnLatency>> = _latencies

    fun track(event: PipelineEvent) {
        val data = turnData.getOrPut(event.turnId) { mutableMapOf() }
        
        when (event) {
            is PipelineEvent.SpeechStarted -> data["start"] = event.tStart
            is PipelineEvent.SpeechEnded -> data["end"] = event.tEnd
            is PipelineEvent.Transcribed -> data["asr"] = event.tAsrDone
            is PipelineEvent.Translated -> {
                data["mt"] = event.tMtDone
                updateLatencies(event.turnId)
            }
            is PipelineEvent.PlaybackStarted -> {
                data["tts"] = event.tPlaybackStarted
                updateLatencies(event.turnId)
            }
            else -> {}
        }
    }

    private fun updateLatencies(turnId: String) {
        val data = turnData[turnId] ?: return
        val start = data["start"] ?: 0
        val end = data["end"] ?: 0
        val asr = data["asr"] ?: 0
        val mt = data["mt"] ?: 0
        val tts = data["tts"] ?: 0

        val latency = TurnLatency(
            turnId = turnId,
            captureMs = if (start > 0 && end > 0) end - start else 0,
            asrMs = if (end > 0 && asr > 0) asr - end else 0,
            mtMs = if (asr > 0 && mt > 0) mt - asr else 0,
            ttsMs = if (mt > 0 && tts > 0) tts - mt else 0,
            e2eMs = if (end > 0 && mt > 0) mt - end else 0
        )

        val current = _latencies.value.toMutableList()
        val index = current.indexOfFirst { it.turnId == turnId }
        if (index >= 0) {
            current[index] = latency
        } else {
            current.add(latency)
        }
        _latencies.value = current.takeLast(10)
    }
}
