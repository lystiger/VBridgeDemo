package com.example.demovbridge.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.demovbridge.ui.theme.*
import com.example.demovbridge.pipeline.CaptureMode
import com.example.demovbridge.pipeline.MeetingState

@Composable
fun ParticipantHeaderCard(
    direction: String,
    onSwap: () -> Unit = {}
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp)),
        color = SurfaceSubtle,
        shape = RoundedCornerShape(12.dp),
        onClick = onSwap
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "PARTICIPANT · YOU",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextMuted,
                    letterSpacing = 0.1.sp
                )
                Text(
                    text = direction,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            }
            
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(PrimaryCyan.copy(alpha = 0.1f), RoundedCornerShape(999.dp))
                    .border(1.dp, BorderActiveCyan, RoundedCornerShape(999.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text("⇄", color = BrightCyan, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun VBridgeBottomNav() {
    // Hidden as per brief minimalist tone or kept very subtle
}
