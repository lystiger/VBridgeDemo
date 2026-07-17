package com.example.demovbridge

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.demovbridge.benchmark.LatencyTracer
import com.example.demovbridge.benchmark.TurnLatency
import com.example.demovbridge.pipeline.Direction
import com.example.demovbridge.pipeline.MeetingViewModel
import com.example.demovbridge.pipeline.PipelineTelemetry
import com.example.demovbridge.ui.conversation.VBridgeConversation
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MeetingViewModel
    private val latencyTracer = LatencyTracer()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted: Boolean ->
        if (isGranted) {
            viewModel.startPipeline()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel = MeetingViewModel(applicationContext, "demo-room-1")

        lifecycleScope.launch {
            viewModel.pipelineEvents.collect { latencyTracer.track(it) }
        }

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val isReady by viewModel.isReady.collectAsState()
                    
                    if (!isReady) {
                        LoadingScreen()
                    } else {
                        MainScreen(
                            viewModel = viewModel, 
                            latencyTracer = latencyTracer,
                            onRecord = { checkAndStartRecording() }
                        )
                    }
                }
            }
        }
    }

    private fun checkAndStartRecording() {
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.startPipeline()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel.stopPipeline()
    }
}

@Composable
fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text("Initializing AI Models...", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun MainScreen(
    viewModel: MeetingViewModel, 
    latencyTracer: LatencyTracer,
    onRecord: () -> Unit
) {
    val uiTurns by viewModel.turns.collectAsState()
    val direction by viewModel.currentDirection.collectAsState()
    val latencies by latencyTracer.latencies.collectAsState()
    val telemetry by viewModel.telemetry.collectAsState()
    var isRecording by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize()) {
        // Telemetry & Latency HUD
        TelemetryHud(telemetry, latencies.lastOrNull())

        // Header with Toggle and Record
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = {
                viewModel.toggleDirection()
            }) {
                Text(if (direction == Direction.ViToEn) "VN → EN" else "EN → VN")
            }

            Button(
                onClick = {
                    if (isRecording) {
                        viewModel.stopPipeline()
                    } else {
                        onRecord()
                    }
                    isRecording = !isRecording
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) Color.Red else MaterialTheme.colorScheme.primary
                )
            ) {
                Text(if (isRecording) "Stop" else "Record")
            }
        }

        VBridgeConversation(
            turns = uiTurns,
            onRetry = viewModel::retryTurn,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
fun TelemetryHud(telemetry: PipelineTelemetry, latestLatency: TurnLatency?) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (telemetry.systemPssMemoryMb > 400) Color.Red.copy(alpha = 0.2f) 
                             else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
        )
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("RTF: %.2f".format(telemetry.currentRtf), style = MaterialTheme.typography.labelSmall)
                Text("P95: ${telemetry.p95LatencyMs}ms", style = MaterialTheme.typography.labelSmall)
                Text("RAM: ${telemetry.systemPssMemoryMb}MB", 
                    style = MaterialTheme.typography.labelSmall,
                    color = if (telemetry.systemPssMemoryMb > 400) Color.Red else Color.Unspecified
                )
                Text("Threads: ${telemetry.activeThreadCount}", style = MaterialTheme.typography.labelSmall)
            }
            if (latestLatency != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Last Latency: E2E: ${latestLatency.e2eMs}ms | VAD: ${latestLatency.vadMs}ms | ASR: ${latestLatency.asrMs}ms | MT: ${latestLatency.mtMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.8f
                )
            }
        }
    }
}
