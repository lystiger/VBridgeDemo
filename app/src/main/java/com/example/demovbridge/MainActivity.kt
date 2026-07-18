package com.example.demovbridge

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.demovbridge.benchmark.LatencyTracer
import com.example.demovbridge.benchmark.TurnLatency
import com.example.demovbridge.data.ParticipantConfig
import com.example.demovbridge.data.SettingsManager
import com.example.demovbridge.network.NetworkEvent
import com.example.demovbridge.pipeline.Direction
import com.example.demovbridge.pipeline.MeetingState
import com.example.demovbridge.pipeline.MeetingViewModel
import com.example.demovbridge.pipeline.PipelineTelemetry
import com.example.demovbridge.ui.conversation.VBridgeConversation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.UUID

class MainActivity : ComponentActivity() {

    private lateinit var settingsManager: SettingsManager
    private var viewModel: MeetingViewModel? = null
    private val latencyTracer = LatencyTracer()

    private var microphonePermissionGranted by mutableStateOf(false)

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        microphonePermissionGranted = granted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsManager = SettingsManager(applicationContext)

        microphonePermissionGranted =
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val config by settingsManager.config.collectAsState(initial = null)
                    var currentConfig by remember { mutableStateOf<ParticipantConfig?>(null) }
                    
                    LaunchedEffect(config) {
                        if (config != null && currentConfig == null) {
                            currentConfig = config
                            viewModel = MeetingViewModel(applicationContext, config!!)
                        }
                    }

                    if (currentConfig == null) {
                        SetupScreen(onJoin = { newConfig ->
                            lifecycleScope.launch {
                                settingsManager.saveConfig(newConfig)
                                currentConfig = newConfig
                                viewModel = MeetingViewModel(applicationContext, newConfig)
                            }
                        })
                    } else {
                        val vm = viewModel
                        if (vm == null) {
                            LoadingScreen()
                        } else {
                            val isReady by vm.isReady.collectAsState()
                            if (!isReady) {
                                LoadingScreen()
                            } else {
                                LaunchedEffect(vm) {
                                    vm.pipelineEvents.collect { latencyTracer.track(it) }
                                }

                                MainScreen(
                                    viewModel = vm,
                                    latencyTracer = latencyTracer,
                                    onRequestMicrophonePermission = ::requestMicrophone,
                                    hasPermission = microphonePermissionGranted
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        microphonePermissionGranted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
    }

    private fun requestMicrophone() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            // Denied once, can still ask — show the dialog
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else if (!microphonePermissionGranted) {
            // Permanently denied (or first time). Try launch; if nothing happens, open Settings.
            requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
            })
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel?.stopPipeline()
    }
}

@Composable
fun SetupScreen(onJoin: (ParticipantConfig) -> Unit) {
    var name by remember { mutableStateOf("") }
    var room by remember { mutableStateOf("") }
    var language by remember { mutableStateOf("vi") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("VBridge Setup", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(32.dp))
        
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Display Name") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        OutlinedTextField(
            value = room,
            onValueChange = { room = it },
            label = { Text("Room Code") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(24.dp))
        
        Text("Your Speaking Language:", style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = language == "vi", onClick = { language = "vi" })
            Text("Vietnamese")
            Spacer(modifier = Modifier.width(16.dp))
            RadioButton(selected = language == "en", onClick = { language = "en" })
            Text("English")
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Button(
            onClick = {
                if (name.isNotBlank() && room.isNotBlank()) {
                    onJoin(
                        ParticipantConfig(
                            participantId = UUID.randomUUID().toString(),
                            displayName = name,
                            roomId = room,
                            sourceLanguage = language,
                            targetLanguage = if (language == "vi") "en" else "vi"
                        )
                    )
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Join Room")
        }
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
fun PermissionWarning() {
    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Red.copy(alpha = 0.1f))
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Microphone permission is required for translation.",
                color = Color.Red,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
fun MainScreen(
    viewModel: MeetingViewModel, 
    latencyTracer: LatencyTracer,
    onRequestMicrophonePermission: () -> Unit,
    hasPermission: Boolean
) {
    val uiTurns by viewModel.turns.collectAsState()
    val direction by viewModel.currentDirection.collectAsState()
    val latencies by latencyTracer.latencies.collectAsState()
    val telemetry by viewModel.telemetry.collectAsState()
    val meetingState by viewModel.meetingState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()

    Column(modifier = Modifier.fillMaxSize()) {
        ConnectionStatusBar(connectionState, meetingState)
        TelemetryHud(telemetry, latencies.lastOrNull())

        if (!hasPermission) {
            PermissionWarning()
        }

        VBridgeConversation(
            turns = uiTurns,
            onRetry = viewModel::retryTurn,
            modifier = Modifier.weight(1f)
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 8.dp,
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = { viewModel.toggleDirection() }) {
                    Text(if (direction == Direction.ViToEn) "VN → EN" else "EN → VN")
                }

                val isRecording = meetingState == MeetingState.Recording
                val isProcessing = meetingState is MeetingState.ProcessingAsr || meetingState is MeetingState.Translating
                val isPlaying = meetingState == MeetingState.PlayingRemoteTts
                
                val bg = when {
                    isRecording -> Color.Red
                    isProcessing -> Color.Gray
                    isPlaying -> Color.Blue.copy(alpha = 0.5f)
                    else -> MaterialTheme.colorScheme.primary
                }

                Box(
                    modifier = Modifier
                        .height(64.dp)
                        .weight(1f)
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(50))
                        .background(bg)
                        .pointerInput(hasPermission) {
                            awaitPointerEventScope {
                                while (true) {
                                    awaitFirstDown()
                                    if (!hasPermission) {
                                        onRequestMicrophonePermission()
                                    } else {
                                        viewModel.startRecording()
                                        waitForUpOrCancellation()
                                        viewModel.stopRecording()
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when {
                            isRecording -> "RELEASE TO SEND"
                            isProcessing -> "PROCESSING..."
                            isPlaying -> "REMOTE SPEAKING"
                            else -> "HOLD TO SPEAK"
                        },
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun ConnectionStatusBar(connection: NetworkEvent, meeting: MeetingState) {
    val (text, color) = when (connection) {
        is NetworkEvent.Connected -> "Connected" to Color(0xFF4CAF50)
        is NetworkEvent.Connecting -> "Connecting..." to Color(0xFFFFC107)
        is NetworkEvent.Disconnected -> "Disconnected" to Color.Red
        is NetworkEvent.Error -> "Network Error" to Color.Red
        else -> "Disconnected" to Color.Red
    }

    Surface(
        color = color.copy(alpha = 0.1f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(vertical = 4.dp, horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(8.dp).background(color, shape = MaterialTheme.shapes.small))
                Spacer(modifier = Modifier.width(8.dp))
                Text(text, style = MaterialTheme.typography.labelSmall, color = color)
            }
            
            if (meeting is MeetingState.Error) {
                Text(meeting.message, style = MaterialTheme.typography.labelSmall, color = Color.Red)
            }
        }
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
