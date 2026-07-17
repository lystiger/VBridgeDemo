package com.example.demovbridge.pipeline

import android.app.ActivityManager
import android.content.Context
import android.os.Debug
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val _telemetry = MutableStateFlow(PipelineTelemetry())
    val telemetry: StateFlow<PipelineTelemetry> = _telemetry.asStateFlow()

    private val latencyHistory = ConcurrentLinkedQueue<Long>()

    init {
        startMonitoring()
    }

    private fun startMonitoring() {
        scope.launch {
            while (isActive) {
                updateSystemTelemetry()
                delay(2000)
            }
        }
    }

    private fun updateSystemTelemetry() {
        val memoryInfo = Debug.MemoryInfo()
        Debug.getMemoryInfo(memoryInfo)
        val pssTotal = memoryInfo.totalPss / 1024

        val threads = Thread.activeCount()
        
        _telemetry.value = _telemetry.value.copy(
            systemPssMemoryMb = pssTotal,
            activeThreadCount = threads
        )

        if (pssTotal > 400) {
            Log.e("VBridgeDiag", "CRITICAL: Memory ceiling exceeded! PSS: ${pssTotal}MB > 400MB")
        }
    }

    fun recordAsrPerformance(audioDurationMs: Long, processingTimeMs: Long) {
        val rtf = if (audioDurationMs > 0) processingTimeMs.toFloat() / audioDurationMs else 0f
        _telemetry.value = _telemetry.value.copy(currentRtf = rtf)
    }

    fun recordLatency(latencyMs: Long) {
        latencyHistory.add(latencyMs)
        if (latencyHistory.size > 100) latencyHistory.poll()
        _telemetry.value = _telemetry.value.copy(p95LatencyMs = calculateP95())
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
