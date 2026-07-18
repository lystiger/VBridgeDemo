package com.example.demovbridge.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.demovbridge.ui.theme.*

@Composable
fun VBridgeBackground(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AppBackground)
    ) {
        // Technical Grid
        Canvas(modifier = Modifier.fillMaxSize()) {
            val gridSize = 64.dp.toPx()
            val gridColor = Color(0x101F2937) // rgba(31, 41, 55, 0.063)
            
            // Vertical lines
            var x = 0f
            while (x < size.width) {
                drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                x += gridSize
            }
            
            // Horizontal lines
            var y = 0f
            while (y < size.height) {
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                y += gridSize
            }
        }

        // Atmospheric Glows (Simplified for mobile)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(PrimaryCyan.copy(alpha = 0.08f), Color.Transparent),
                        center = Offset(0f, 0f),
                        radius = 800f
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.radialGradient(
                        colors = listOf(IndigoAccent.copy(alpha = 0.08f), Color.Transparent),
                        center = Offset(1000f, 1500f),
                        radius = 1000f
                    )
                )
        )

        content()
    }
}

@Composable
fun VBridgeLogo(modifier: Modifier = Modifier) {
    val logoGradient = Brush.linearGradient(
        colors = listOf(BrightCyan, TealAccent, LightIndigo)
    )
    
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(logoGradient),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "V",
                color = Color.White,
                fontWeight = FontWeight.Black,
                fontSize = 20.sp
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = "VBRIDGE",
            style = MaterialTheme.typography.labelLarge.copy(brush = logoGradient),
            letterSpacing = 0.08.sp
        )
    }
}

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    borderColor: Color = BorderDefault,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp)),
        color = SurfaceSubtle,
        content = {
            Column(
                modifier = Modifier.padding(20.dp),
                content = content
            )
        }
    )
}

@Composable
fun StatusBadge(
    text: String,
    statusColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        color = statusColor.copy(alpha = 0.1f),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, statusColor.copy(alpha = 0.3f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(statusColor, RoundedCornerShape(999.dp))
            )
            Text(
                text = text.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun VBridgeWaveform(
    isListening: Boolean,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "waveform")
    
    val barScales = (0..4).map { i ->
        if (isListening) {
            transition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis = when(i) {
                            0 -> 600; 1 -> 400; 2 -> 800; 3 -> 500; else -> 700
                        },
                        delayMillis = i * 100,
                        easing = FastOutSlowInEasing
                    ),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "bar-$i"
            )
        } else {
            remember { mutableStateOf(0.3f) }
        }
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        barScales.forEach { scale ->
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(24.dp)
                    .graphicsLayer(scaleY = scale.value)
                    .background(BrightCyan, RoundedCornerShape(999.dp))
            )
        }
    }
}

@Composable
fun PushToTalkButton(
    isPressed: Boolean,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scale by animateFloatAsState(if (isPressed) 0.94f else 1f, label = "scale")
    val glowSize by animateDpAsState(if (isPressed) 24.dp else 0.dp, label = "glow")
    
    val gradient = Brush.linearGradient(colors = listOf(PrimaryCyan, IndigoAccent))
    
    Box(
        modifier = modifier
            .size(112.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale),
        contentAlignment = Alignment.Center
    ) {
        // Outer rings
        Box(
            modifier = Modifier
                .size(92.dp + glowSize)
                .background(PrimaryCyan.copy(alpha = 0.15f), RoundedCornerShape(999.dp))
        )
        
        Surface(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(999.dp))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            onPressStart()
                            tryAwaitRelease()
                            onPressEnd()
                        }
                    )
                },
            color = Color.Transparent
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(gradient),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = androidx.compose.material.icons.Icons.Rounded.Mic,
                    contentDescription = "Speak",
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

@Composable
fun VBridgeButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    text: String,
    primary: Boolean = true
) {
    val gradient = Brush.linearGradient(colors = listOf(PrimaryCyan, IndigoAccent))
    
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(12.dp),
        colors = if (primary) ButtonDefaults.buttonColors(containerColor = Color.Transparent) 
                 else ButtonDefaults.buttonColors(containerColor = DeepPanel),
        contentPadding = PaddingValues()
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (primary) Modifier.background(gradient) else Modifier.border(1.dp, BorderDefault, RoundedCornerShape(12.dp))),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = text.uppercase(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = if (primary) Color.White else TextSecondary
            )
        }
    }
}
