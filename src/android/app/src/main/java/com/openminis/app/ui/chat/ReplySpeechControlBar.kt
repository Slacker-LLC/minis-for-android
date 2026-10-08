package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.speech.ReplySpeechState
import com.openminis.app.speech.VoiceOutputState
import com.openminis.app.ui.chat.voice.SpeakerGlyph
import com.openminis.app.ui.chat.voice.VoiceOutputPickerSheet

/**
 * The one control for everything that is being read aloud, mounted above the composer where it survives list
 * recycling and scrolling: a reply read from its own button, a selection read from the selection bar, or a reply
 * being read as it arrives ("read replies"). It carries what the floating speaker used to: the voice (tap to
 * switch), the speed (tap to cycle), mute and stop, besides pause and progress.
 */
@Composable
fun ReplySpeechControlBar(
    state: ReplySpeechState,
    onTogglePause: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    LaunchedEffect(Unit) { VoiceOutputState.init(context) }
    val muted by VoiceOutputState.isMuted.collectAsState()
    val speed by VoiceOutputState.speed.collectAsState()
    val speaking by VoiceOutputState.isSpeaking.collectAsState()
    val synthesizing by VoiceOutputState.isSynthesizing.collectAsState()
    val modelLabel by VoiceOutputState.activeModelLabel.collectAsState()
    val systemVoiceLabel by VoiceOutputState.systemVoiceLabel.collectAsState()

    // A reply read as it arrives ("read replies") has no sentence list of its own: the bar shows while it speaks.
    val automatic = !state.isActive && (speaking || synthesizing)
    if (!state.isActive && !automatic) return

    val title = when {
        automatic -> stringResource(R.string.reply_speech_auto)
        state.displayIndex > 0 -> stringResource(R.string.reply_speech_title, state.displayIndex)
        else -> stringResource(R.string.reply_speech_selection)
    }
    val statusText = when {
        automatic -> stringResource(if (synthesizing && !speaking) R.string.reply_speech_preparing else R.string.reply_speech_reading)
        state.status == ReplySpeechState.Status.READING -> stringResource(R.string.reply_speech_reading)
        state.status == ReplySpeechState.Status.PAUSED -> stringResource(R.string.reply_speech_paused)
        state.status == ReplySpeechState.Status.COMPLETED -> stringResource(R.string.reply_speech_completed)
        else -> ""
    }
    val progress = if (state.totalSentences > 0) {
        (state.currentSentence.toFloat() / state.totalSentences.toFloat()).coerceIn(0f, 1f)
    } else 0f
    var showVoicePicker by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 4.dp,
        shadowElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!automatic) {
                        IconButton(onClick = onTogglePause, modifier = Modifier.size(36.dp)) {
                            Icon(
                                imageVector = if (state.status == ReplySpeechState.Status.PAUSED) {
                                    Icons.Default.PlayArrow
                                } else {
                                    Icons.Default.Pause
                                },
                                contentDescription = if (state.status == ReplySpeechState.Status.PAUSED) stringResource(R.string.resume_action) else stringResource(R.string.common_pause),
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    IconButton(
                        // Stopping a read reply ends that reading; for a reply read as it arrives it also turns
                        // "read replies" off, which is what the floating speaker's close did.
                        onClick = if (automatic) ({ VoiceOutputState.setEnabled(false) }) else onClose,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = stringResource(R.string.common_close),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The voice: tap to switch (the same picker the rest of the app uses).
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .widthIn(max = 180.dp)
                        .clip(RoundedCornerShape(50))
                        .clickable { showVoicePicker = true }
                        .padding(vertical = 2.dp, horizontal = 2.dp),
                ) {
                    Icon(
                        Icons.Default.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        modelLabel ?: systemVoiceLabel ?: stringResource(R.string.tts_capsule_system_engine),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(3.dp))
                    Icon(
                        Icons.Default.UnfoldMore,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(12.dp),
                    )
                }
                Spacer(Modifier.width(10.dp))
                // The speed: tap to cycle the steps. Fixed size so cycling does not jitter.
                Box(
                    Modifier
                        .size(width = 46.dp, height = 22.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.15f),
                            RoundedCornerShape(50),
                        )
                        .clickable { VoiceOutputState.nextSpeed() },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        VoiceOutputState.speedLabel(speed),
                        maxLines = 1,
                        color = if (speed > 1.0f) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        style = androidx.compose.ui.text.TextStyle(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            lineHeight = 11.sp,
                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
                        ),
                    )
                }
                Spacer(Modifier.weight(1f))
                // Mute: silences without ending the reading.
                Box(
                    Modifier.size(32.dp).clip(RoundedCornerShape(50)).clickable { VoiceOutputState.setMuted(!muted) },
                    contentAlignment = Alignment.Center,
                ) {
                    SpeakerGlyph(muted = muted, synthesizing = synthesizing, ring = 28.dp)
                }
            }
            if (!automatic) {
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(RoundedCornerShape(2.dp)),
                )
            }
        }
    }

    if (showVoicePicker) {
        val repo = (LocalContext.current.applicationContext as? MinisApp)?.providerRepository
        if (repo != null) {
            VoiceOutputPickerSheet(providerRepository = repo, onDismiss = { showVoicePicker = false })
        }
    }
}
