package com.example.demovbridge.pipeline

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Diagnostics data holder for VBridge Pipeline performance monitoring.
 * Adheres to AGENTS.md guardrails regarding memory and thread caps.
 */
data class PipelineTelemetry(
    val currentRtf: Float = 0.0f,
    val p95LatencyMs: Long = 0L,
    val systemPssMemoryMb: Int = 0,
    val activeThreadCount: Int = 0,
    val lastStage: String = "Idle"
)

class PipelineDiagnostics(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val _telemetry = MutableSharedFlow<PipelineTelemetry>(extraBufferCapacity = 16)
    val telemetry: SharedFlow<PipelineTelemetry> = _telemetry.asSharedFlow()

    private val latencyHistory = ConcurrentLinkedQueue<Long>()
    private val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    init {
        startMonitoring()
    }

    private fun startMonitoring() {
        scope.launch {
            while (isActive) {
                emitTelemetry()
                delay(2000) // Monitor every 2 seconds
            }
        }
    }

    private suspend fun emitTelemetry(rtf: Float = 0.0f, stage: String = "Idle") {
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)
        val pssTotal = memoryInfo.totalPss / 1024 // KB to MB

        val threads = Thread.activeCount()
        
        val p95 = calculateP95()

        val data = PipelineTelemetry(
            currentRtf = rtf,
            p95LatencyMs = p95,
            systemPssMemoryMb = pssTotal,
            activeThreadCount = threads,
            lastStage = stage
        )

        _telemetry.emit(data)
        
        // Log critical violations from AGENTS.md
        if (pssTotal > 400) {
            Log.e("VBridgeDiag", "CRITICAL: Memory ceiling exceeded! PSS: ${pssTotal}MB > 400MB")
        }
        
        Log.d("VBridgeDiag", "Telemetry: RTF=$rtf, P95=${p95}ms, PSS=${pssTotal}MB, Threads=$threads")
    }

    fun recordEvent(event: PipelineEvent) {
        when (event) {
            is PipelineEvent.Translated -> {
                // For P95, we track end-to-end latency from ASR start to MT end
                // Note: Simplified logic here, ideally we use timestamps from PipelineEvent
            }
            else -> {}
        }
    }

    fun recordAsrPerformance(audioDurationMs: Long, processingTimeMs: Long) {
        val rtf = if (audioDurationMs > 0) processingTimeMs.toFloat() / audioDurationMs else 0f
        scope.launch { emitTelemetry(rtf, "ASR") }
    }

    fun recordLatency(latencyMs: Long) {
        latencyHistory.add(latencyMs)
        if (latencyHistory.size > 100) latencyHistory.poll()
    }

    private fun calculateP95(): Long {
        val sorted = latencyHistory.toList().sorted()
        if (sorted.isEmpty()) return 0L
        val index = (sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)
        return sorted[index]
    }

    fun stop() {
        scope.cancel()
    }
}
