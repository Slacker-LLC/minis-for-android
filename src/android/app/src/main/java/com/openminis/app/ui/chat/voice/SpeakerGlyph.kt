package com.openminis.app.ui.chat.voice

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** Speaker icon with a rotating 75° arc while cloud TTS is synthesizing. */
@Composable
internal fun SpeakerGlyph(muted: Boolean, synthesizing: Boolean, ring: androidx.compose.ui.unit.Dp) {
    Box(contentAlignment = Alignment.Center) {
        Icon(
            if (muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
            contentDescription = null,
            tint = if (muted) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        if (synthesizing && !muted) {
            val transition = rememberInfiniteTransition(label = "synthArc")
            val angle by transition.animateFloat(
                initialValue = 0f,
                targetValue = 360f,
                animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart),
                label = "synthAngle",
            )
            val arcColor = MaterialTheme.colorScheme.primary
            Canvas(Modifier.size(ring)) {
                drawArc(
                    color = arcColor,
                    startAngle = angle,
                    sweepAngle = 75f,
                    useCenter = false,
                    style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round),
                    topLeft = Offset(0.75.dp.toPx(), 0.75.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(
                        size.width - 1.5.dp.toPx(),
                        size.height - 1.5.dp.toPx(),
                    ),
                )
            }
        }
    }
}
