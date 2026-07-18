package com.example.demovbridge.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.demovbridge.ui.conversation.ConversationTurn
import com.example.demovbridge.ui.conversation.TurnStatus

@Composable
fun ChatHistoryList(
    messages: List<ConversationTurn>,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        items(messages, key = { it.id }) { message ->
            ChatBubble(message = message)
        }
    }
}

@Composable
fun ChatBubble(
    message: ConversationTurn,
    modifier: Modifier = Modifier
) {
    val isYou = message.isLocal
    val alignment = if (isYou) Alignment.End else Alignment.Start
    val bubbleColor = if (isYou) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val textColor = if (isYou) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    val subTextColor = if (isYou) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Text(
            text = if (isYou) "YOU" else message.speakerName.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
            modifier = Modifier.padding(bottom = 4.dp)
        )
        
        Surface(
            color = bubbleColor,
            shape = RoundedCornerShape(
                topStart = 20.dp,
                topEnd = 20.dp,
                bottomStart = if (isYou) 20.dp else 4.dp,
                bottomEnd = if (isYou) 4.dp else 20.dp
            ),
            tonalElevation = 2.dp,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (message.status == TurnStatus.Complete || message.status == TurnStatus.Error) {
                    Text(
                        text = message.sourceText,
                        style = MaterialTheme.typography.bodySmall,
                        color = subTextColor
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (message.status == TurnStatus.Error) message.errorMessage ?: "Error" else message.translatedText,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = if (message.status == TurnStatus.Error) MaterialTheme.colorScheme.error else textColor
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = textColor,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Processing...",
                        style = MaterialTheme.typography.bodySmall,
                        color = subTextColor
                    )
                }
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${message.direction.sourceLabel} → ${message.direction.targetLabel}",
                            style = MaterialTheme.typography.labelSmall,
                            color = subTextColor
                        )
                        message.latencyMs?.let {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${it}ms",
                                style = MaterialTheme.typography.labelSmall,
                                color = subTextColor
                            )
                        }
                    }
                    
                    if (message.status == TurnStatus.Complete) {
                        Row {
                            IconButton(
                                onClick = { /* TODO */ },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Play",
                                    tint = textColor,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            IconButton(
                                onClick = { /* TODO */ },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "Copy",
                                    tint = textColor,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
