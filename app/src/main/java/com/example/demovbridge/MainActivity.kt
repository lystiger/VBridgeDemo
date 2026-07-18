package com.example.demovbridge

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.demovbridge.benchmark.LatencyTracer
import com.example.demovbridge.data.ParticipantConfig
import com.example.demovbridge.data.SettingsManager
import com.example.demovbridge.network.NetworkEvent
import com.example.demovbridge.pipeline.CaptureMode
import com.example.demovbridge.pipeline.ConnectivityMode
import com.example.demovbridge.pipeline.Direction
import com.example.demovbridge.pipeline.Floor
import com.example.demovbridge.pipeline.MeetingState
import com.example.demovbridge.pipeline.MeetingViewModel
import com.example.demovbridge.pipeline.MtEngine
import com.example.demovbridge.ui.components.*
import com.example.demovbridge.ui.conversation.VBridgeConversation
import com.example.demovbridge.ui.theme.*
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
                VBridgeBackground {
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
        VBridgeLogo(modifier = Modifier.padding(bottom = 8.dp))
        Text(
            "Real-time bilingual conversations",
            style = MaterialTheme.typography.bodyMedium,
            color = TextSecondary
        )
        StatusBadge(
            text = "LOCAL-FIRST AI TRANSLATION",
            statusColor = PrimaryCyan,
            modifier = Modifier.padding(top = 16.dp)
        )
        Spacer(modifier = Modifier.height(48.dp))

        GlassCard {
            Text("Start or join a conversation", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text("Create a room and invite another device.", style = MaterialTheme.typography.bodySmall, color = TextMuted, modifier = Modifier.padding(bottom = 24.dp))

            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Your Name") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            Spacer(modifier = Modifier.height(16.dp))
            OutlinedTextField(value = room, onValueChange = { room = it }, label = { Text("Room Code") }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp))
            Spacer(modifier = Modifier.height(24.dp))

            Text("INITIAL SPOKEN LANGUAGE (AUTO-DETECTED)", style = MaterialTheme.typography.labelSmall, color = TextMuted)
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("vi" to "VIETNAMESE", "en" to "ENGLISH").forEach { (code, label) ->
                    val selected = language == code
                    Surface(
                        modifier = Modifier.weight(1f).height(44.dp).border(1.dp, if (selected) BorderActiveCyan else BorderSubtle, RoundedCornerShape(8.dp)),
                        color = if (selected) PrimaryCyan.copy(alpha = 0.1f) else Color.Transparent,
                        shape = RoundedCornerShape(8.dp),
                        onClick = { language = code }
                    ) {
                        Box(contentAlignment = Alignment.Center) { Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) BrightCyan else TextSecondary) }
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
            VBridgeButton(onClick = { if (name.isNotBlank() && room.isNotBlank()) onJoin(ParticipantConfig(UUID.randomUUID().toString(), name, room, language, if (language == "vi") "en" else "vi")) }, text = "Join Room", primary = true)
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
        containerColor = Color.Transparent,
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 16.dp)) {
                // FR-3: Caption thay đổi theo Floor
                when (floor) {
                    Floor.RemoteSpeaking -> Text("PARTNER IS SPEAKING", style = MaterialTheme.typography.labelSmall, color = TextMuted, fontWeight = FontWeight.Bold)
                    Floor.LocalSpeaking -> Text("LISTENING…", style = MaterialTheme.typography.labelSmall, color = BrightCyan, fontWeight = FontWeight.Bold)
                    Floor.Open -> Text(if (captureMode == CaptureMode.HandsOn) "HOLD TO SPEAK" else "TAP TO SPEAK", style = MaterialTheme.typography.labelSmall, color = TextMuted, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.height(12.dp))

                RecordingMicFAB(
                    isRecording = meetingState == MeetingState.Recording,
                    enabled = floor == Floor.Open, // FR-3: Vô hiệu hóa nút nếu không phải Open
                    captureMode = captureMode,
                    onToggle = {
                        if (!hasPermission) onRequestMicrophonePermission()
                        else if (meetingState == MeetingState.Recording) viewModel.stopRecording()
                        else viewModel.startRecording()
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
        Column(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                ParticipantHeaderCard(direction = if (direction == Direction.ViToEn) "VIETNAMESE → ENGLISH" else "ENGLISH → VIETNAMESE")
            }
            VBridgeConversation(
                turns = uiTurns,
                onRetry = viewModel::retryTurn,
                onToggleRecording = {
                    if (!hasPermission) onRequestMicrophonePermission()
                    else if (meetingState == MeetingState.Recording) viewModel.stopRecording()
                    else viewModel.startRecording()
                },
                captureHint = if (captureMode == CaptureMode.HandsOn) "Hold to speak." else "Tap to start/stop.",
                isListening = meetingState == MeetingState.Recording,
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
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("Session settings", style = MaterialTheme.typography.headlineSmall)
            SettingChoices("Connectivity", listOf("Solo", "Room"), if (connectivityMode == ConnectivityMode.Solo) 0 else 1) { onConnectivityModeChange(if (it == 0) ConnectivityMode.Solo else ConnectivityMode.Room) }
            SettingChoices("Translation engine", listOf("On-device", "Remote"), if (mtEngine == MtEngine.OnDevice) 0 else 1) { onMtEngineChange(if (it == 0) MtEngine.OnDevice else MtEngine.Remote) }
            SettingChoices("Capture", listOf("Hands-on", "Hands-free"), if (captureMode == CaptureMode.HandsOn) 0 else 1) { onCaptureModeChange(if (it == 0) CaptureMode.HandsOn else CaptureMode.HandsFree) }
        }
    }
}

@Composable
private fun SettingChoices(title: String, choices: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEachIndexed { index, label ->
                FilterChip(selected = selectedIndex == index, onClick = { onSelect(index) }, label = { Text(label) })
            }
        }
    }
}