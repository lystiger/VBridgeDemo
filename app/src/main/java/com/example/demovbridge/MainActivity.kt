package com.example.demovbridge

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.demovbridge.benchmark.LatencyTracer
import com.example.demovbridge.data.ParticipantConfig
import com.example.demovbridge.data.SettingsManager
import com.example.demovbridge.network.NetworkEvent
import com.example.demovbridge.pipeline.Direction
import com.example.demovbridge.pipeline.MeetingState
import com.example.demovbridge.pipeline.MeetingViewModel
import com.example.demovbridge.pipeline.Floor
import com.example.demovbridge.pipeline.CaptureMode
import com.example.demovbridge.pipeline.ConnectivityMode
import com.example.demovbridge.pipeline.MtEngine
import com.example.demovbridge.ui.conversation.VBridgeConversation
import com.example.demovbridge.ui.theme.VBridgeTheme
import com.example.demovbridge.ui.components.*
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
            VBridgeTheme {
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
                                    onRequestMicrophonePermission = {
                                        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                    },
                                    hasPermission = microphonePermissionGranted,
                                    onBack = {
                                        vm.stopPipeline()
                                        viewModel = null
                                        currentConfig = null
                                    }
                                )
                            }
                        }
                    }
                }
            }
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
    var room by remember { mutableStateOf("test-room") }
    var language by remember { mutableStateOf("vi") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("VBridge", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text("Real-time Translation", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f))
        
        Spacer(modifier = Modifier.height(48.dp))
        
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Display Name") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        OutlinedTextField(
            value = room,
            onValueChange = { room = it },
            label = { Text("Room Code") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
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
            modifier = Modifier.fillMaxWidth().height(56.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text("JOIN CONVERSATION", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text("Initializing VBridge...", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun MainScreen(
    viewModel: MeetingViewModel, 
    onRequestMicrophonePermission: () -> Unit,
    hasPermission: Boolean,
    onBack: () -> Unit
) {
    val uiTurns by viewModel.turns.collectAsState()
    val direction by viewModel.currentDirection.collectAsState()
    val meetingState by viewModel.meetingState.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val floor by viewModel.floor.collectAsState()
    val captureMode by viewModel.captureMode.collectAsState()
    val connectivityMode by viewModel.mode.collectAsState()
    val mtEngine by viewModel.mtEngine.collectAsState()
    var showSettings by rememberSaveable { mutableStateOf(false) }

    val (connText, connColor) = when {
        connectivityMode == ConnectivityMode.Solo -> "Solo" to Color(0xFF64748B)
        connectionState is NetworkEvent.Connected -> "Connected" to Color(0xFF22C55E)
        connectionState is NetworkEvent.Connecting -> "Connecting…" to Color(0xFFFFC107)
        connectionState is NetworkEvent.Error -> "Error" to Color.Red
        else -> "Disconnected" to Color.Gray
    }

    if (showSettings) {
        SettingsSheet(
            connectivityMode = connectivityMode,
            mtEngine = mtEngine,
            captureMode = captureMode,
            onConnectivityModeChange = viewModel::setConnectivityMode,
            onMtEngineChange = viewModel::setMtEngine,
            onCaptureModeChange = viewModel::setCaptureMode,
            onDismiss = { showSettings = false }
        )
    }

    Scaffold(
        topBar = {
            VBridgeTopBar(
                connectionText = connText,
                connectionColor = connColor,
                onBackClick = onBack,
                onSettingsClick = { showSettings = true }
            )
        },
        bottomBar = { VBridgeBottomNav() },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                when (floor) {
                    Floor.RemoteSpeaking -> Text(
                        "It's their turn…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Floor.LocalSpeaking -> Text(
                        "Listening…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Floor.Open -> Unit
                }
                Spacer(modifier = Modifier.height(4.dp))
                RecordingMicFAB(
                    isRecording = meetingState == MeetingState.Recording,
                    enabled = floor != Floor.RemoteSpeaking,
                    captureMode = captureMode,
                    onToggle = {
                        if (!hasPermission) {
                            onRequestMicrophonePermission()
                        } else {
                            if (meetingState == MeetingState.Recording) {
                                viewModel.stopRecording()
                            } else {
                                viewModel.startRecording()
                            }
                        }
                    },
                    onPressStart = {
                        if (hasPermission) viewModel.startRecording()
                        else onRequestMicrophonePermission()
                    },
                    onPressEnd = {
                        if (hasPermission) viewModel.stopRecording()
                    }
                )
            }
        },
        floatingActionButtonPosition = FabPosition.Center
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            ParticipantHeaderCard(
                direction = if (direction == Direction.ViToEn) "VN → EN" else "EN → VN",
                onSwap = { viewModel.toggleDirection() }
            )
            
            if (!hasPermission) {
                Card(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(
                        "Microphone permission required.",
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            VBridgeConversation(
                turns = uiTurns,
                onRetry = viewModel::retryTurn,
                captureHint = if (captureMode == CaptureMode.HandsOn) {
                    "Hold the microphone and speak."
                } else {
                    "Tap the microphone to start and stop."
                },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    connectivityMode: ConnectivityMode,
    mtEngine: MtEngine,
    captureMode: CaptureMode,
    onConnectivityModeChange: (ConnectivityMode) -> Unit,
    onMtEngineChange: (MtEngine) -> Unit,
    onCaptureModeChange: (CaptureMode) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text("Session settings", style = MaterialTheme.typography.headlineSmall)
            SettingChoices(
                title = "Connectivity",
                choices = listOf("Solo", "Room"),
                selectedIndex = if (connectivityMode == ConnectivityMode.Solo) 0 else 1,
                onSelect = { onConnectivityModeChange(if (it == 0) ConnectivityMode.Solo else ConnectivityMode.Room) }
            )
            SettingChoices(
                title = "Translation engine",
                choices = listOf("On-device", "Remote"),
                selectedIndex = if (mtEngine == MtEngine.OnDevice) 0 else 1,
                onSelect = { onMtEngineChange(if (it == 0) MtEngine.OnDevice else MtEngine.Remote) }
            )
            SettingChoices(
                title = "Capture",
                choices = listOf("Hands-on", "Hands-free"),
                selectedIndex = if (captureMode == CaptureMode.HandsOn) 0 else 1,
                onSelect = { onCaptureModeChange(if (it == 0) CaptureMode.HandsOn else CaptureMode.HandsFree) }
            )
        }
    }
}

@Composable
private fun SettingChoices(
    title: String,
    choices: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEachIndexed { index, label ->
                FilterChip(
                    selected = selectedIndex == index,
                    onClick = { onSelect(index) },
                    label = { Text(label) }
                )
            }
        }
    }
}
