package com.openminis.app.ui.chat

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.runtime.mutableStateOf

// [T-android-split-chat] Self-contained "thinking / streaming" dot indicators
// extracted verbatim from ChatScreen.kt. `internal` so the chat package can
// still reference them. No logic change — code moved as-is.

@Composable
internal fun BouncingDots(color: Color) {
    val infiniteTransition = rememberInfiniteTransition(label = "bounce")
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(3) { i ->
            val offset by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = -2f,
                animationSpec = infiniteRepeatable(
                    animation = tween(350, delayMillis = i * 120),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot_$i",
            )
            Box(
                modifier = Modifier
                    .size(4.dp)
                    .padding(top = (-offset).dp.coerceAtLeast(0.dp))
                    .background(color, CircleShape),
            )
        }
    }
}

// iOS-style streaming "..." after tool title — 3 dots bouncing inline with text
@Composable
internal fun StreamingDotsText() {
    val infiniteTransition = rememberInfiniteTransition(label = "streamDots")
    Row {
        repeat(3) { i ->
            val offset by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = -3f,
                animationSpec = infiniteRepeatable(
                    animation = tween(350, delayMillis = i * 120, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "sdot_$i",
            )
            Text(
                text = ".",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.offset(y = offset.dp),
            )
        }
    }
}

// ─── Typing Indicator (three dots pulsing) ────────────────────────────────────

@Composable
internal fun TypingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "typing")
    // Live Soul name → "<custom name> is thinking…" when the user renamed
    // the assistant in Soul settings. SoulStore.cachedMetadata is a StateFlow
    // that's updated on save (SoulSettingsScreen) and at app start
    // (MinisApp.onCreate via refreshCache); collectAsState makes Compose
    // recompose the indicator immediately when it changes.
    val soulMeta by com.openminis.app.agent.SoulStore.cachedMetadata.collectAsState()
    val soulName = soulMeta.name.trim().ifEmpty { "Minis" }

    Row(
        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = stringResource(R.string.chat_typing_indicator, soulName),
            fontSize = 15.sp,
            color = ChatColors.tertiaryText,
        )
        // Animated bouncing dots
        val dots = listOf(".", ".", ".")
        dots.forEachIndexed { index, dot ->
            val offsetY by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = -6f,
                animationSpec = infiniteRepeatable(
                    animation = tween(400, delayMillis = index * 150, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot_bounce_$index",
            )
            Text(
                text = dot,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = ChatColors.tertiaryText,
                modifier = Modifier.graphicsLayer { translationY = offsetY },
            )
        }
    }
}

@Composable
internal fun CompactProgressIndicator(
    progress: ChatViewModel.CompactProgress,
    onCancel: () -> Unit,
) {
    // Re-reads the clock every second; the changing value is what makes the
    // row demonstrably alive.
    var elapsedSec by remember(progress.startedAtMs) { mutableStateOf(0) }
    LaunchedEffect(progress.startedAtMs) {
        while (true) {
            elapsedSec = ((System.currentTimeMillis() - progress.startedAtMs) / 1000L).toInt()
            kotlinx.coroutines.delay(1000)
        }
    }

    Row(
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (progress.depth > 0) {
                // Only surfaced once a split actually happened — saying
                // "segment 1" on the common single-call path would imply a
                // complexity that isn't there.
                stringResource(
                    R.string.compact_progress_split,
                    elapsedSec,
                    progress.callsIssued,
                    progress.callBudget,
                )
            } else {
                stringResource(R.string.compact_progress, elapsedSec)
            },
            fontSize = 14.sp,
            color = ChatColors.tertiaryText,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.cancel),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable(onClick = onCancel)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

// ─── Typing Indicator (three dots pulsing) ────────────────────────────────────


/**
 * The turn's one clock, directly under the user's message: "Working · 12s" with moving dots from the moment the
 * message was sent until the whole reply is done, then "Completed · took 38s". Nothing else in the turn shows a time.
 */
@Composable
internal fun TurnClockLine(startedAtMs: Long, endedAtMs: Long?, live: Boolean) {
    var elapsedSec by androidx.compose.runtime.remember(startedAtMs, live) { androidx.compose.runtime.mutableStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(startedAtMs, live) {
        if (live && startedAtMs > 0L) {
            while (true) {
                elapsedSec = ((System.currentTimeMillis() - startedAtMs) / 1000L).coerceAtLeast(0L)
                kotlinx.coroutines.delay(1_000L)
            }
        }
    }
    val text = if (live) {
        val working = androidx.compose.ui.res.stringResource(com.openminis.app.R.string.work_status_running)
        // A turn whose send time is unknown shows no number rather than a wrong one.
        if (startedAtMs <= 0L) working else working + " · " + formatStepDuration(elapsedSec, stillRunning = false)
    } else {
        val done = androidx.compose.ui.res.stringResource(com.openminis.app.R.string.work_status_done)
        val took = endedAtMs?.takeIf { startedAtMs > 0L }?.let { (it - startedAtMs) / 1000L }?.takeIf { it >= 0L }
        if (took == null) done else done + " · " + androidx.compose.ui.res.stringResource(
            com.openminis.app.R.string.work_process_duration, formatStepDuration(took, stillRunning = false),
        )
    }
    androidx.compose.foundation.layout.Row(
        modifier = androidx.compose.ui.Modifier.padding(bottom = 6.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Text(
            text = text,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = com.openminis.app.ui.theme.ChatColors.secondaryText,
        )
        if (live) {
            androidx.compose.foundation.layout.Spacer(androidx.compose.ui.Modifier.width(6.dp))
            BouncingDots(com.openminis.app.ui.theme.ChatColors.secondaryText)
        }
    }
}

/** The centred "今天 9:56" line (12 / 16, secondary grey). */
@Composable
internal fun TimeDividerLine(epochMs: Long) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val text = androidx.compose.runtime.remember(epochMs) { formatChatTimestamp(context, epochMs) }
    androidx.compose.foundation.layout.Box(
        modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 6.dp),
        contentAlignment = androidx.compose.ui.Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            text = text,
            fontSize = 12.sp,
            lineHeight = 16.sp,
            color = com.openminis.app.ui.theme.ChatColors.secondaryText,
        )
    }
}

/** Today / yesterday with the time, an older date with the time otherwise. */
internal fun formatChatTimestamp(context: android.content.Context, epochMs: Long, now: Long = System.currentTimeMillis()): String {
    val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(epochMs))
    val day = java.util.Calendar.getInstance().apply { timeInMillis = epochMs }
    val today = java.util.Calendar.getInstance().apply { timeInMillis = now }
    fun sameDay(a: java.util.Calendar, b: java.util.Calendar) =
        a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR) && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR)
    if (sameDay(day, today)) return context.getString(com.openminis.app.R.string.chat_time_today, time)
    val yesterday = (today.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, -1) }
    if (sameDay(day, yesterday)) return context.getString(com.openminis.app.R.string.chat_time_yesterday, time)
    val date = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(epochMs))
    return "$date $time"
}
