package com.example.demovbridge.ui.conversation

import android.provider.Settings
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
// Domain model
// ---------------------------------------------------------------------------

enum class TurnDirection(val sourceLabel: String, val targetLabel: String) {
    ViToEn(sourceLabel = "Tiếng Việt", targetLabel = "English"),
    EnToVi(sourceLabel = "English", targetLabel = "Tiếng Việt"),
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
    val latencyMs: Long? = null
)

// ---------------------------------------------------------------------------
// Motion
// ---------------------------------------------------------------------------

private const val MOTION_MS = 220

@Composable
private fun rememberMotionDurationMs(base: Int = MOTION_MS): Int {
    val resolver = LocalContext.current.contentResolver
    val scale = remember {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }
    return (base * scale).toInt().coerceAtLeast(1)
}

// ---------------------------------------------------------------------------
// Public entry point
// ---------------------------------------------------------------------------

@Composable
fun VBridgeConversation(
    turns: List<ConversationTurn>,
    modifier: Modifier = Modifier,
    onRetry: (turnId: String) -> Unit = {},
    captureHint: String = "Hold the microphone and speak.",
) {
    val activeDirection = turns.lastOrNull()?.direction ?: TurnDirection.ViToEn

    Column(modifier = modifier.fillMaxSize().statusBarsPadding()) {
        LanguageSwapHeader(
            direction = activeDirection,
            modifier = Modifier.fillMaxWidth(),
        )

        if (turns.isEmpty()) {
            EmptyState(captureHint = captureHint, modifier = Modifier.fillMaxSize())
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(turns, key = { it.id }) { turn ->
                @OptIn(ExperimentalFoundationApi::class)
                TranslationTurnCard(
                    turn = turn,
                    onRetry = { onRetry(turn.id) },
                    modifier = Modifier.animateItem(
                        placementSpec = tween(MOTION_MS),
                    ),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Header
// ---------------------------------------------------------------------------

@Composable
private fun LanguageSwapHeader(
    direction: TurnDirection,
    modifier: Modifier = Modifier,
) {
    val durationMs = rememberMotionDurationMs()
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedContent(
                targetState = direction,
                contentAlignment = Alignment.Center,
                transitionSpec = {
                    (slideInVertically(tween(durationMs)) { it / 2 } + fadeIn(tween(durationMs)))
                        .togetherWith(fadeOut(tween(durationMs)))
                },
                label = "language-swap",
            ) { dir ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = dir.sourceLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = "  ⇄  ",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = dir.targetLabel,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Turn card
// ---------------------------------------------------------------------------

@Composable
private fun TranslationTurnCard(
    turn: ConversationTurn,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val durationMs = rememberMotionDurationMs()
    val fromLeadingEdge = turn.isLocal

    val transitionState = remember {
        MutableTransitionState(initialState = false).apply { targetState = true }
    }

    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = if (fromLeadingEdge) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        AnimatedVisibility(
            visibleState = transitionState,
            enter = slideInHorizontally(tween(durationMs)) { fullWidth ->
                if (fromLeadingEdge) fullWidth / 3 else -fullWidth / 3
            } + fadeIn(tween(durationMs)),
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth(0.85f)
                    .wrapContentWidth(if (fromLeadingEdge) Alignment.End else Alignment.Start),
                shape = RoundedCornerShape(
                    topStart = 16.dp,
                    topEnd = 16.dp,
                    bottomStart = if (fromLeadingEdge) 16.dp else 4.dp,
                    bottomEnd = if (fromLeadingEdge) 4.dp else 16.dp,
                ),
                colors = CardDefaults.cardColors(
                    containerColor = if (fromLeadingEdge) MaterialTheme.colorScheme.primaryContainer 
                                     else MaterialTheme.colorScheme.secondaryContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (turn.isLocal) "You" else turn.speakerName,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (fromLeadingEdge) MaterialTheme.colorScheme.primary 
                                     else MaterialTheme.colorScheme.secondary
                        )
                        DirectionChip(direction = turn.direction)
                    }

                    Text(
                        text = turn.sourceText,
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    TurnBody(
                        turn = turn,
                        durationMs = durationMs,
                        onRetry = onRetry,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun DirectionChip(direction: TurnDirection) {
    Text(
        text = "${direction.sourceLabel} → ${direction.targetLabel}",
        style = MaterialTheme.typography.labelSmall,
        fontSize = 10.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
    )
}

@Composable
private fun TurnBody(
    turn: ConversationTurn,
    durationMs: Int,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(
        targetState = turn.status,
        transitionSpec = { fadeIn(tween(durationMs)).togetherWith(fadeOut(tween(durationMs))) },
        label = "turn-status",
        modifier = modifier,
    ) { status ->
        when (status) {
            TurnStatus.Transcribing -> TranslatingIndicator(label = "Transcribing…")
            TurnStatus.Translating -> TranslatingIndicator(label = "Translating…")
            TurnStatus.Complete -> Text(
                text = turn.translatedText,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            TurnStatus.Error -> TurnErrorRow(
                message = turn.errorMessage ?: "Translation failed",
                onRetry = onRetry,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Loading + error + empty states
// ---------------------------------------------------------------------------

@Composable
private fun TranslatingIndicator(label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val transition = rememberInfiniteTransition(label = "dots")
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.25f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 600, delayMillis = index * 150),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot-$index",
            )
            Text(
                text = "●",
                fontSize = 8.sp,
                modifier = Modifier
                    .padding(end = 4.dp)
                    .alpha(alpha),
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TurnErrorRow(
    message: String,
    onRetry: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f, fill = false),
        )
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}

@Composable
private fun EmptyState(captureHint: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "No turns yet",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "$captureHint The translation will appear here.",
                modifier = Modifier.padding(top = 4.dp, start = 32.dp, end = 32.dp),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Preview
// ---------------------------------------------------------------------------

@Preview(showBackground = true, name = "Conversation - Empty")
@Composable
private fun PreviewEmpty() {
    MaterialTheme {
        VBridgeConversation(turns = emptyList())
    }
}

@Preview(showBackground = true, name = "Conversation - Mixed States")
@Composable
private fun PreviewMixed() {
    val mockTurns = listOf(
        ConversationTurn(
            id = "1",
            speakerId = "user1",
            speakerName = "Anh",
            isLocal = true,
            direction = TurnDirection.ViToEn,
            sourceText = "Xin chào, bạn khỏe không?",
            translatedText = "Hello, how are you?",
            status = TurnStatus.Complete
        ),
        ConversationTurn(
            id = "2",
            speakerId = "user2",
            speakerName = "Partner",
            isLocal = false,
            direction = TurnDirection.EnToVi,
            sourceText = "I'm doing well, thank you! How about you?",
            translatedText = "Tôi khỏe, cảm ơn! Còn bạn thì sao?",
            status = TurnStatus.Complete
        ),
        ConversationTurn(
            id = "3",
            speakerId = "user1",
            speakerName = "Anh",
            isLocal = true,
            direction = TurnDirection.ViToEn,
            sourceText = "Tôi cũng khỏe.",
            translatedText = "",
            status = TurnStatus.Translating
        ),
        ConversationTurn(
            id = "4",
            speakerId = "user2",
            speakerName = "Partner",
            isLocal = false,
            direction = TurnDirection.EnToVi,
            sourceText = "That's good to hear.",
            translatedText = "",
            status = TurnStatus.Error,
            errorMessage = "Connection lost"
        )
    )

    MaterialTheme {
        VBridgeConversation(
            turns = mockTurns,
            onRetry = {}
        )
    }
}
