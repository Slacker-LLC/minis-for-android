package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.ui.components.MinisModalBottomSheet
import com.openminis.app.ui.glass.GlassSheetWindowBlur
import com.openminis.app.ui.glass.glassSheetSurface
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.theme.LocalUiStyle
import com.openminis.app.ui.theme.UiStyle
import com.openminis.app.ui.theme.minisSheetColor
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// iOS ChatColors equivalent
internal val ToolCheckColor = Color(0xFF34C759) // iOS .green

internal val ToolErrorColor = Color(0xFFFF3B30) // iOS .red

internal val ToolCancelColor = Color(0xFFFFCC00) // iOS .yellow

// Memory tool accent — matches iOS `.pink` on SF Symbols.
internal val ToolMemoryAccent = Color(0xFFFF2D55)

// Sparkle gradient colors (iOS uses linear gradient)

// T129: cap photo/video and file pickers at 50 items per launch. Above this
// count Android's PickMultipleVisualMedia silently truncates anyway, but our
// document picker has no native cap — so we apply the same limit on both
// sides and toast the user when their selection is trimmed. Mirrors iOS
// PHPickerConfiguration.selectionLimit = 50.
internal const val ATTACHMENT_PICK_LIMIT = 50

/**
 * [T-android-send-no-autoscroll-behind-preview] Follow-grace window after a
 * user message append: within it the reserve-change pin bypasses the
 * isNearBottom gate (send intent is unambiguous; the freshly-inserted rows
 * make the live anchor transiently read "not at bottom").
 */
internal const val SEND_FOLLOW_GRACE_MS = 2_000L

// [T-android-stream-end-arm-race] How long after streaming→idle the
// position-driven userScrolledAway net stays suppressed, so the final
// markdown reflow (which lands ~100-150 ms after _isStreaming clears and
// changes row heights just like streaming growth did) cannot arm the flag
// and neuter the stream-end re-pin. Comfortably covers the settle LE's own
// 220 ms delay plus its 900 ms verification pass.
internal const val STREAM_END_ARM_GRACE_MS = 1_500L

/**
 * [T-slash-picker-fixed-height port from iOS 73f1b94a] Locked popup
 * height for the slash and mention pickers: up to 4 rows are visible,
 * any overflow scrolls. Computed as `rowHeight * visibleRows + 8dp`.
 * [T-android-slash-menu-density] Rows were tightened (vertical padding
 * 10→7dp) so rowHeight ≈ 42dp covers a 14sp title + 11sp subtitle + 7dp
 * vertical padding; 42*4 + 8 ≈ 176dp. Keeps 4 rows visible with no extra
 * blank space at the bottom.
 */
internal val SLASH_PICKER_FIXED_HEIGHT: Dp = 176.dp

internal val SLASH_PICKER_MAX_HEIGHT: Dp = 280.dp

internal const val SLASH_PICKER_VISIBLE_ROWS = 4

internal val SparkleColor1 = Color(0xFFB8B096) // rgb(0.72, 0.69, 0.59)

internal val SparkleColor2 = Color(0xFF99998C) // rgb(0.6, 0.6, 0.55)

internal val CHAT_MAX_CONTENT_WIDTH = 900.dp

@Composable
internal fun slashPickerHeight(
    titleSp: TextUnit = 14.sp,
    subtitleSp: TextUnit = 11.sp,
    verticalPadding: Dp = 7.dp,
    iconSize: Dp = 18.dp,
): Dp {
    val fontScale = LocalDensity.current.fontScale
    if (fontScale <= 1f) return SLASH_PICKER_FIXED_HEIGHT

    val density = LocalDensity.current
    return with(density) {
        val textHeight = (titleSp.toDp() + subtitleSp.toDp()) * 1.2f
        val rowHeight = maxOf(textHeight, iconSize) + verticalPadding * 2
        (rowHeight * SLASH_PICKER_VISIBLE_ROWS + 8.dp)
            .coerceIn(SLASH_PICKER_FIXED_HEIGHT, SLASH_PICKER_MAX_HEIGHT)
    }
}

/**
 * Draw a thin scroll thumb on the right edge of a [LazyColumn] (or any
 * scrollable) so the user can see at a glance that the list overflows
 * and is scrollable — mirrors iOS `.scrollIndicators(.visible)` which
 * Compose does not provide out of the box for LazyColumn.
 *
 * The thumb fades in while scrolling / shortly after, similar to the
 * platform scrollbar.
 */
internal fun Modifier.verticalScrollbar(
    listState: androidx.compose.foundation.lazy.LazyListState,
    width: Dp = 3.dp,
    color: Color = Color(0x55888888),
): Modifier = this.then(Modifier.drawWithContent {
    drawContent()
    val layoutInfo = listState.layoutInfo
    val totalItems = layoutInfo.totalItemsCount
    val visibleItems = layoutInfo.visibleItemsInfo
    if (totalItems == 0 || visibleItems.isEmpty()) return@drawWithContent
    if (visibleItems.size >= totalItems &&
        visibleItems.first().index == 0 &&
        visibleItems.last().index == totalItems - 1 &&
        visibleItems.first().offset >= 0
    ) {
        // Fully visible, no scroll possible — no thumb.
        return@drawWithContent
    }
    val firstIndex = visibleItems.first().index
    val firstOffsetPx = visibleItems.first().offset.toFloat()
    val avgItemSize = visibleItems.sumOf { it.size }.toFloat() / visibleItems.size
    val totalContentPx = avgItemSize * totalItems
    val viewportHeight = this.size.height
    if (totalContentPx <= viewportHeight || avgItemSize <= 0f) return@drawWithContent
    val scrollOffsetPx = firstIndex * avgItemSize - firstOffsetPx
    val thumbHeight = (viewportHeight * (viewportHeight / totalContentPx)).coerceAtLeast(24f)
    val maxScroll = (totalContentPx - viewportHeight).coerceAtLeast(1f)
    val maxTop = (viewportHeight - thumbHeight).coerceAtLeast(0f)
    val thumbTop = (scrollOffsetPx / maxScroll * maxTop).coerceIn(0f, maxTop)
    val widthPx = width.toPx()
    drawRoundRect(
        color = color,
        topLeft = androidx.compose.ui.geometry.Offset(this.size.width - widthPx - 1f, thumbTop),
        size = androidx.compose.ui.geometry.Size(widthPx, thumbHeight),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(widthPx / 2, widthPx / 2),
    )
})

// [T-android-tool-autoscroll] Combined signal for the streaming auto-follow
// LaunchedEffect. data class so distinctUntilChanged uses structural equality
// — any field flip propagates a tick. Per-block (id, kind, status, length)
// folded into [blockSig] (FNV-1a 64-bit hash) so a RUNNING→SUCCESS flip on a
// tool block, a new block appearing (id flips), or a kind change all wake the
// collector even when growth/size/awaiting alone would have stayed equal.
internal data class ScrollFollowKey(
    val lastIndex: Int,
    val growth: Long,
    val toolBlockCount: Int,
    val awaiting: Boolean,
    val blockSig: Long,
)

// [T-android-split-chat] UserMessageBubble / UserAttachmentList /
// FileAttachmentTile / fileIconFor / ImageGalleryDialog moved verbatim to
// ChatUserMessageUI.kt.
// [T-android-split-chat] FlatChatItem / mergeStreamingOverlay / buildFlatChatItems
// moved verbatim to ChatFlatItems.kt (now internal).

// [T-android-split-chat] AssistantHeader / AssistantMessageView /
// BoundsTrackedBlock / InlineErrorBanner / ToolStopButton /
// formatToolDetailsForClipboard / ToolCallPill / ThinkingBlock moved verbatim
// to ChatAssistantMessageUI.kt.

// ─── Tool Detail Bottom Sheet (iOS: ToolLiveSheet — nav bar + content + bottom bar) ──

// [T-android-split-chat] ToolDetailSheet + helpers (extractShellCommand,
// extractPartialJsonString, chunkToolOutput, initialRevealChunks,
// LazyRevealToolText, EditorCard) moved verbatim to ChatToolDetailUI.kt.
// [T-android-split-chat] AttachmentChip / InputCircleButton / MicButton /
// ToolPreviewThumbnail / FloatingToolStatusBar / ThinkingLevelPicker moved
// verbatim to ChatComposerWidgets.kt.

// [T-android-split-chat] createCameraOutputUri / getFileName /
// PendingNonTextSelection moved verbatim to ChatScreenHelpers.kt (now internal).


// [T-android-split-chat] fuzzyMatch / ModelPickerSheet / providerDotColor moved
// verbatim to ChatModelPickerSheet.kt (ModelPickerSheet now internal).

// [T-android-split-chat] BorderedMarkdownTable / FallbackInfoBlock /
// CompactSummarySheet / parseInlineMarkdown / rememberBrowserLiveSnapshot /
// ResumeBanner / SwipeToSendHint moved verbatim to ChatMiscViews.kt.
// Sun May 24 11:01:25 CST 2026

/**
 * [T-android-thinking-badge-navbar] Compact thinking-level pill shown on the
 * navbar's "provider · model" line (iOS AIChatView.thinkingLevelBadge parity).
 *
 * Deliberately smaller than the 11sp model-name text next to it — a 9dp
 * lightbulb + 9sp level label — so it reads as secondary auxiliary info and
 * never crowds out the model name. Uses [Icons.Default.Lightbulb], the same
 * glyph the `/thinking` slash command uses.
 *
 * Colors mirror iOS AIChatView.thinkingLevelBadge exactly — a NEUTRAL look, not
 * an accent one. iOS uses `foregroundStyle(secondaryText)` on a
 * `Capsule().fill(Color.secondary.opacity(0.10))` background; the Compose
 * equivalents are `onSurfaceVariant` (secondary grey) for the icon+label and
 * `onSurface.copy(alpha = 0.08f)` (a faint translucent grey) for the capsule.
 * We deliberately do NOT use `primary` / `primaryContainer` / the app's blue
 * thinking accent here: the badge is passive status ("thinking is on, at this
 * level"), not a call-to-action, so a blue highlight would over-emphasize it
 * and clash with the grey "provider · model" text it sits beside. Both colors
 * are theme tokens, so the badge adapts to light/dark automatically.
 *
 * Also mounted when thinking is Off on a reasoning-capable model (iOS parity,
 * e6bd75efc): the pill then reads icon + "Off" as a tap target for enabling
 * deep thinking. The icon is dimmed to 0.4 alpha in that state, matching the
 * Off-row convention in [ThinkingLevelSheet].
 *
 * It carries its OWN clickable (which consumes the tap) so a tap on the badge
 * opens the thinking-level sheet instead of the model picker owned by the
 * enclosing subtitle Column — see the call site for the full gesture-separation
 * rationale.
 */
@Composable
internal fun ThinkingLevelBadge(
    level: com.openminis.app.data.model.ThinkingLevel,
    onClick: () -> Unit,
) {
    val context = LocalContext.current
    // Secondary grey for icon + label (iOS secondaryText parity) — no accent.
    val badgeColor = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            // Faint translucent-grey capsule (iOS Color.secondary.opacity(0.10)).
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            // Own clickable → consumes the tap, opens the thinking sheet.
            .clickable(onClick = onClick)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Icon(
            imageVector = Icons.Default.Lightbulb,
            contentDescription = null,
            // Dimmed in the Off state (sheet Off-row convention) so "Off" reads
            // as "thinking disabled" at a glance.
            tint = if (level.isEnabled) badgeColor else badgeColor.copy(alpha = 0.4f),
            modifier = Modifier.size(9.dp),
        )
        Text(
            text = level.localizedName(context),
            fontSize = 9.sp,
            lineHeight = 11.sp,
            fontWeight = FontWeight.Medium,
            color = badgeColor,
            maxLines = 1,
        )
    }
}

/**
 * [T-android-thinking-badge-navbar] Bottom-sheet thinking-level selector opened
 * from [ThinkingLevelBadge]. Mirrors iOS ThinkingLevelSheetView: an Off row
 * followed by every level the current model supports; the active level shows a
 * trailing check. Selecting any row calls [onSelect] (which also dismisses).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun ThinkingLevelSheet(
    currentLevel: com.openminis.app.data.model.ThinkingLevel,
    availableLevels: List<com.openminis.app.data.model.ThinkingLevel>,
    onSelect: (com.openminis.app.data.model.ThinkingLevel) -> Unit,
    onDismiss: () -> Unit,
) {
    // Off is always offered (turns thinking off); availableLevels already
    // excludes Off, so prepend it. De-dup defensively in case a caller ever
    // includes it.
    val rows = remember(availableLevels) {
        listOf(com.openminis.app.data.model.ThinkingLevel.OFF) +
            availableLevels.filter { it != com.openminis.app.data.model.ThinkingLevel.OFF }
    }
    MinisModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = if (LocalUiStyle.current == UiStyle.GLASS) Color.Transparent else minisSheetColor(),
    ) {
        GlassSheetWindowBlur()
        Column(modifier = Modifier.fillMaxWidth().glassSheetSurface().padding(bottom = 12.dp)) {
            Text(
                text = stringResource(R.string.thinking_level_sheet_title),
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = ChatColors.primaryText,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            HorizontalDivider(color = ChatColors.toolBorder, thickness = 0.5.dp)
            val context = LocalContext.current
            rows.forEach { level ->
                // "Off selected" = the current level is disabled; otherwise an
                // exact match.
                val isSelected = if (level == com.openminis.app.data.model.ThinkingLevel.OFF) {
                    !currentLevel.isEnabled
                } else {
                    currentLevel == level
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(level) }
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Lightbulb,
                        contentDescription = null,
                        tint = ChatColors.thinking.copy(
                            alpha = if (level == com.openminis.app.data.model.ThinkingLevel.OFF) 0.4f else 1f,
                        ),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = level.localizedName(context),
                        fontSize = 15.sp,
                        color = ChatColors.primaryText,
                        modifier = Modifier.weight(1f),
                    )
                    if (isSelected) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = ChatColors.thinking,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Localized name of a thinking level, for the composer chip and the level sheet. */
@Composable
internal fun thinkingLevelLabel(level: ThinkingLevel): String = stringResource(
    when (level) {
        ThinkingLevel.OFF -> R.string.thinking_level_off
        ThinkingLevel.LOW -> R.string.thinking_level_low
        ThinkingLevel.MEDIUM -> R.string.thinking_level_medium
        ThinkingLevel.HIGH -> R.string.thinking_level_high
        ThinkingLevel.XHIGH -> R.string.thinking_level_xhigh
        ThinkingLevel.MAX -> R.string.thinking_level_max
        ThinkingLevel.ULTRA -> R.string.thinking_level_ultra
    },
)
