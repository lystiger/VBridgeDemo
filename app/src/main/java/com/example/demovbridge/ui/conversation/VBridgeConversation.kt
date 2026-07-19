package com.example.demovbridge.ui.conversation

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.demovbridge.ui.components.StatusBadge
import com.example.demovbridge.ui.theme.*
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------
// Domain model
// ---------------------------------------------------------------------------

enum class TurnDirection(val sourceLabel: String, val targetLabel: String) {
    ViToEn(sourceLabel = "Vietnamese", targetLabel = "English"),
    EnToVi(sourceLabel = "English", targetLabel = "Vietnamese"),
}

enum class TurnStatus { Transcribing, Translating, Complete, Error }

data class ConversationTurn(
    val id: String,
    val speakerId: String,
    val speakerName: String,
    val isLocal: Boolean,
    val direction: TurnDirection,
    val sourceText: String,
    val translatedText: String,
    val status: TurnStatus,
    val errorMessage: String? = null,
    val latencyMs: Long? = null,
    val occurredAtEpochMs: Long = System.currentTimeMillis()
)

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

private const val MOTION_MS = 400

@Composable
private fun rememberMotionDurationMs(default: Int = MOTION_MS): Int {
    return default
}

// ---------------------------------------------------------------------------
// Main UI
// ---------------------------------------------------------------------------

@Composable
fun VBridgeConversation(
    turns: List<ConversationTurn>,
    onRetry: (String) -> Unit,
    modifier: Modifier = Modifier,
    isOnline: Boolean = false,
    onToggleRecording: (turnId: String) -> Unit = {},
    captureHint: String = "",
    isListening: Boolean = false,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(turns.size) {
        if (turns.isNotEmpty()) {
            scope.launch {
                listState.animateScrollToItem(turns.size - 1)
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 120.dp, top = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(turns.sortedBy { it.occurredAtEpochMs }, key = { it.id }) { turn ->
            TranslationTurnCard(
                turn = turn,
                onRetry = onRetry,
                isOnline = isOnline,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}

@Composable
private fun TranslationTurnCard(
    turn: ConversationTurn,
    onRetry: (String) -> Unit,
    isOnline: Boolean,
    modifier: Modifier = Modifier,
) {
    val durationMs = rememberMotionDurationMs()
    val fromLocal = turn.isLocal

    val transitionState = remember {
        MutableTransitionState(initialState = false).apply { targetState = true }
    }

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = if (fromLocal) Alignment.CenterStart else Alignment.CenterEnd,
    ) {
        AnimatedVisibility(
            visibleState = transitionState,
            enter = slideInVertically(tween(durationMs)) { it / 4 } + fadeIn(tween(durationMs)),
        ) {
            val accentColor = if (fromLocal) PrimaryCyan else IndigoAccent

            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .border(
                        1.dp,
                        accentColor.copy(alpha = 0.4f),
                        RoundedCornerShape(16.dp)
                    )
                    .clip(RoundedCornerShape(16.dp)),
                color = SurfaceSubtle,
            ) {
                // Top accent line
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color.Transparent, accentColor.copy(alpha = 0.4f), Color.Transparent)
                            )
                        )
                )

                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val languageLabel = turn.direction.sourceLabel
                        val speakerLabel = if (turn.isLocal) "YOU" else turn.speakerName.uppercase()

                        // Strict check: Only show language name if isOnline is explicitly true
                        val displayText = if (isOnline) {
                            "${languageLabel.uppercase()} · $speakerLabel"
                        } else {
                            speakerLabel
                        }

                        Text(
                            text = displayText,
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary,
                            fontWeight = FontWeight.Bold
                        )

                        Text(
                            text = remember(turn.occurredAtEpochMs) {
                                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).apply {
                                    timeZone = java.util.TimeZone.getTimeZone("GMT+07:00")
                                }.format(java.util.Date(turn.occurredAtEpochMs))
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted
                        )
                    }

                    Text(
                        text = turn.sourceText,
                        modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = TextPrimary,
                    )

                    TurnBody(
                        turn = turn,
                        durationMs = durationMs,
                        onRetry = onRetry,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun TurnBody(
    turn: ConversationTurn,
    durationMs: Int,
    onRetry: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = turn.status,
        transitionSpec = { fadeIn(tween(durationMs)).togetherWith(fadeOut(tween(durationMs))) },
        label = "turn-status",
        modifier = modifier,
    ) { status ->
        when (status) {
            TurnStatus.Transcribing, TurnStatus.Translating -> TypingIndicator()
            TurnStatus.Complete -> Text(
                text = turn.translatedText,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = BrightCyan,
            )
            TurnStatus.Error -> TurnErrorRow(
                message = turn.errorMessage ?: "Translation failed",
                onRetry = { onRetry(turn.id) },
            )
        }
    }
}

@Composable
private fun TypingIndicator(
    modifier: Modifier = Modifier,
    dotColor: Color = PrimaryCyan
) {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    
    Row(
        modifier = modifier
            .padding(vertical = 12.dp)
            .height(24.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(3) { index ->
            val delay = index * 150
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.6f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 600
                        0.6f at 0
                        1.0f at 300
                        0.6f at 600
                    },
                    repeatMode = RepeatMode.Restart,
                    initialStartOffset = StartOffset(delay)
                ),
                label = "dot-scale"
            )
            
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = keyframes {
                        durationMillis = 600
                        0.3f at 0
                        1.0f at 300
                        0.3f at 600
                    },
                    repeatMode = RepeatMode.Restart,
                    initialStartOffset = StartOffset(delay)
                ),
                label = "dot-alpha"
            )

            Box(
                modifier = Modifier
                    .size(8.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        this.alpha = alpha
                    }
                    .background(dotColor, CircleShape)
            )
        }
    }
}

@Composable
private fun TurnErrorRow(
    message: String,
    onRetry: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
        )
        TextButton(onClick = onRetry) {
            Text("Retry", style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun EmptyState(
    captureHint: String,
    isListening: Boolean,
    onToggleRecording: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Logic for empty state if needed
}
