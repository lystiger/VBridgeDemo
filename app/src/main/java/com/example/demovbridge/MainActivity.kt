package com.example.demovbridge

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.demovbridge.benchmark.LatencyTracer
import com.example.demovbridge.data.ParticipantConfig
import com.example.demovbridge.data.SettingsManager
import com.example.demovbridge.network.NetworkEvent
import com.example.demovbridge.pipeline.Direction
import com.example.demovbridge.pipeline.MeetingState
import com.example.demovbridge.pipeline.MeetingViewModel
import com.example.demovbridge.ui.conversation.TurnDirection
import com.example.demovbridge.ui.conversation.VBridgeConversation
import kotlinx.coroutines.launch

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
                                    onRequestMicrophonePermission = ::requestMicrophone,
                                    hasPermission = microphonePermissionGranted,
                                    onBack = {
                                        vm.destroy()
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

    private fun requestMicrophone() {
        requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    override fun onDestroy() {
        super.onDestroy()
        viewModel?.destroy()
    }
}

@Composable
fun SetupScreen(onJoin: (ParticipantConfig) -> Unit) {
    var name by remember { mutableStateOf("") }
    var room by remember { mutableStateOf("test-room") }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("VBridge Demo", style = MaterialTheme.typography.headlineMedium, color = Color(0xFF128C7E))
        Spacer(modifier = Modifier.height(32.dp))
        
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Your Name") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(16.dp))
        
        OutlinedTextField(
            value = room,
            onValueChange = { room = it },
            label = { Text("Room ID") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(32.dp))
        
        Button(
            onClick = {
                if (name.isNotBlank() && room.isNotBlank()) {
                    onJoin(ParticipantConfig(
                        participantId = java.util.UUID.randomUUID().toString(),
                        displayName = name,
                        roomId = room,
                        sourceLanguage = "vi",
                        targetLanguage = "en"
                    ))
                }
            },
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF25D366)),
            shape = RoundedCornerShape(28.dp)
        ) {
            Text("JOIN MEETING", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun LoadingScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = Color(0xFF128C7E))
            Spacer(modifier = Modifier.height(16.dp))
            Text("Initializing pipeline...", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun PermissionWarning() {
    Card(
        modifier = Modifier.padding(16.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Text(
            "Microphone permission is required for transcription.",
            modifier = Modifier.padding(16.dp),
            color = MaterialTheme.colorScheme.onErrorContainer
        )
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
    val isOffline by viewModel.isOffline.collectAsState()

    val turnDirection = if (direction == Direction.ViToEn) TurnDirection.ViToEn else TurnDirection.EnToVi

    Column(modifier = Modifier.fillMaxSize()) {
        ConnectionStatusBar(connectionState, meetingState, onBack)

        if (!hasPermission) {
            PermissionWarning()
        }

        VBridgeConversation(
            turns = uiTurns,
            onRetry = viewModel::retryTurn,
            isOffline = isOffline,
            currentDirection = turnDirection,
            participantName = "Remote Participant",
            onToggleDirection = viewModel::toggleDirection,
            modifier = Modifier.weight(1f)
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            tonalElevation = 8.dp,
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier
                    .padding(12.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Language Toggle
                FilledTonalButton(
                    onClick = { viewModel.toggleDirection() },
                    modifier = Modifier.weight(0.35f).height(48.dp),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        if (direction == Direction.ViToEn) "VN → EN" else "EN → VN",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }

                // Online/Offline Toggle
                val offlineColor = Color(0xFF546E7A)
                val onlineColor = Color(0xFF128C7E)
                Button(
                    onClick = { viewModel.toggleOffline() },
                    modifier = Modifier.weight(0.35f).height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (isOffline) offlineColor else onlineColor
                    ),
                    contentPadding = PaddingValues(horizontal = 4.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        if (isOffline) "OFFLINE" else "ONLINE",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                }

                val isRecording = meetingState == MeetingState.Recording
                val isProcessing = meetingState is MeetingState.ProcessingAsr || meetingState is MeetingState.Translating
                val isPlaying = meetingState == MeetingState.PlayingRemoteTts
                
                // Mic Button
                Box(
                    modifier = Modifier
                        .height(48.dp)
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (isRecording) Color(0xFFF44336) else Color(0xFF25D366))
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (!isRecording && !isProcessing && !isPlaying) {
                             Icon(
                                 imageVector = Icons.Default.Mic, 
                                 contentDescription = null, 
                                 tint = Color.White,
                                 modifier = Modifier.size(20.dp)
                             )
                             Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            text = when {
                                isRecording -> "RELEASE"
                                isProcessing -> "..."
                                isPlaying -> "PLAYING"
                                else -> "HOLD"
                            },
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectionStatusBar(connection: NetworkEvent, meeting: MeetingState, onBack: () -> Unit) {
    val (text, color) = when (connection) {
        is NetworkEvent.Connected -> "Connected" to Color(0xFF4CAF50)
        is NetworkEvent.Connecting -> "Connecting..." to Color(0xFFFFC107)
        is NetworkEvent.Disconnected -> "Disconnected" to Color.Gray
        is NetworkEvent.Error -> "Network Error" to Color.Red
        else -> "Disconnected" to Color.Gray
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
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                IconButton(onClick = onBack, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = color, modifier = Modifier.size(16.dp))
                }
                Spacer(modifier = Modifier.width(8.dp))
                Box(modifier = Modifier.size(8.dp).background(color, shape = CircleShape))
                Spacer(modifier = Modifier.width(8.dp))
                Text(text, style = MaterialTheme.typography.labelSmall, color = color)
                
                if (meeting is MeetingState.Error) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(meeting.message, style = MaterialTheme.typography.labelSmall, color = Color.Red, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}
