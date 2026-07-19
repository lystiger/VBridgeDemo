package com.example.demovbridge.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures

@Composable
fun RecordingMicFAB(
    isRecording: Boolean,
    enabled: Boolean,
    pressAndHold: Boolean,
    compact: Boolean = false,
    onPress: () -> Unit,
    onRelease: () -> Unit,
) {
    val buttonSize = if (compact) 48.dp else 72.dp
    val iconSize = if (compact) 22.dp else 32.dp
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    
    // Continuous pulse effect for the background ring
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseScale"
    )
    
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseAlpha"
    )

    // Breathing effect for the main button
    val breathingScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathingScale"
    )

    val containerColor by animateColorAsState(
        targetValue = if (!enabled) Color.Gray 
                     else if (isRecording) Color(0xFFEF4444) // Soft Red
                     else Color(0xFF06B6D4), // Cyan
        animationSpec = tween(durationMillis = 300),
        label = "color"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.padding(24.dp)
    ) {
        // Pulse effect rings when recording
        if (isRecording) {
            Box(
                modifier = Modifier
                    .size(buttonSize)
                    .scale(pulseScale)
                    .background(containerColor.copy(alpha = pulseAlpha), CircleShape)
            )
        }

        Surface(
            modifier = Modifier
                .size(buttonSize)
                .scale(if (isRecording) breathingScale else 1.0f)
                .clip(CircleShape)
                .pointerInput(enabled, pressAndHold) {
                    detectTapGestures(
                        onPress = {
                            if (!enabled) return@detectTapGestures
                            onPress()
                            if (pressAndHold) {
                                tryAwaitRelease()
                                onRelease()
                            }
                        }
                    )
                },
            color = containerColor,
            shape = CircleShape,
            shadowElevation = if (isRecording) 12.dp else 4.dp
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = if (isRecording) "Stop" else "Start",
                    tint = Color.White,
                    modifier = Modifier.size(iconSize)
                )
            }
        }
    }
}
