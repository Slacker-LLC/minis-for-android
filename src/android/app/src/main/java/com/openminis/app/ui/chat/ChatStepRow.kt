package com.openminis.app.ui.chat

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.ui.theme.ChatColors

/**
 * One step of the Agent's work (a thinking block or a tool call). Thinking and tool steps share this
 * row so they read as the same kind of thing: a small leading glyph, one line of title, a quiet
 * trailing note, and a chevron that says what a tap does:
 *
 *  - [StepChevron] pointing right and turning down when the step opens inline (thinking text);
 *  - a fixed right chevron when the step opens a detail sheet (a tool call).
 *
 * No filled capsule, no tinted card: the answer stays the only surface in the turn.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun StepRow(
    title: String,
    modifier: Modifier = Modifier,
    leading: @Composable () -> Unit,
    titleColor: Color = ChatColors.primaryText,
    note: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .heightIn(min = 38.dp)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(10.dp))
        // Title and note share one weighted slot so the trailing chevron always sits at the same x.
        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (note != null) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = note,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = ChatColors.tertiaryText,
                    softWrap = false,
                    maxLines = 1,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** Right-pointing chevron that turns down while [expanded]; the one inline-expand cue of the chat. */
@Composable
internal fun StepChevron(expanded: Boolean, contentDescription: String?) {
    val angle by animateFloatAsState(
        targetValue = if (expanded) 90f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "stepChevron",
    )
    Icon(
        imageVector = Icons.Default.KeyboardArrowRight,
        contentDescription = contentDescription,
        tint = ChatColors.tertiaryText,
        modifier = Modifier.size(16.dp).rotate(angle),
    )
}

/** Chevron for a step that opens a sheet instead of expanding in place. */
@Composable
internal fun StepOpenChevron() {
    Icon(
        imageVector = Icons.Default.KeyboardArrowRight,
        contentDescription = null,
        tint = ChatColors.tertiaryText.copy(alpha = 0.6f),
        modifier = Modifier.size(16.dp),
    )
}
