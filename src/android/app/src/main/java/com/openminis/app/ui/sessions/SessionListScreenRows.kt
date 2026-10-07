package com.openminis.app.ui.sessions

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.ui.components.MinisMenu
import com.openminis.app.ui.components.MinisMenuDivider
import com.openminis.app.ui.components.MinisModalBottomSheet
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.glass.GlassSheetWindowBlur
import com.openminis.app.ui.glass.glassSheetSurface
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.theme.LocalUiStyle
import com.openminis.app.ui.theme.UiStyle
import com.openminis.app.ui.theme.minisSheetColor
import kotlinx.coroutines.launch

@Composable
internal fun FolderCard(
    block: FolderGroupBlock,
    onToggle: () -> Unit,
    onTogglePin: () -> Unit,
    onRename: () -> Unit,
    onDissolve: () -> Unit,
    /** iOS "New Chat in Group": start a chat that files into this folder. */
    onNewChatInGroup: () -> Unit,
    /** iOS "Delete Group & N Sessions": destructive, folder + all members. */
    onDeleteWithSessions: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    // [T-android-menu-press-side] Anchor the long-press menu at the FINGER,
    // not the card. combinedClickable gave no press coordinates, so the menu
    // anchored to the whole card Box and always opened at the card's LEFT
    // edge — pressing the right side popped the menu on the left (captured
    // on-device: left-press and right-press produced pixel-identical menu
    // positions). Same press-point + half-side rule as SessionItemContent.
    var pressOffset by remember { mutableStateOf(DpOffset.Zero) }
    var menuAlignEnd by remember { mutableStateOf(false) }
    val headerPressInteractions = remember { MutableInteractionSource() }
    val density = LocalDensity.current
    val expandLabel = stringResource(
        if (block.isCollapsed) R.string.group_expand else R.string.group_collapse,
    )
    // Expanded-with-members: the card is the container's TOP segment and
    // welds onto the first member row (no bottom gap). Collapsed or empty:
    // a lone floating card. Mirrors iOS FolderCardBackground.
    val isExpandedWithRows = !block.isCollapsed && block.ids.isNotEmpty()
    val chevronRotation by animateFloatAsState(
        targetValue = if (block.isCollapsed) -90f else 0f,
        animationSpec = tween(250),
        label = "folderChevron",
    )
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val dateText = remember(block.latestUpdatedAt, ctx) {
        relativeDate(ctx, block.latestUpdatedAt)
    }
    val headerHaptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    Box(
        modifier = Modifier
            // 6dp outer inset floats the rounded card inside the list width
            // (iOS: the inset frame is what separates it from the full-bleed
            // session rows at a glance). 6 outer + 10 inner = 16 — the folder
            // icon sits exactly on the session rows' alignment grid.
            .padding(
                start = 6.dp, end = 6.dp, top = 4.dp,
                bottom = if (isExpandedWithRows) 0.dp else 4.dp,
            )
            .folderSurface(
                segment = if (isExpandedWithRows) FolderSegment.TOP else FolderSegment.LONE,
                fill = folderFillColor(),
                edge = folderEdgeColor(),
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Clip BEFORE the clickable so the press ripple takes the
                // card's own rounded shape — an unclipped ripple paints a
                // square highlight over the rounded surface. Expanded: only
                // the top corners are round (the card is the container's TOP
                // segment), so the ripple must stay square at the weld.
                .clip(
                    if (isExpandedWithRows) {
                        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                    } else {
                        RoundedCornerShape(16.dp)
                    },
                )
                // detectTapGestures instead of combinedClickable so the
                // long-press OFFSET is available for menu anchoring; the
                // ripple is driven by hand through the InteractionSource
                // (same pattern as SessionRow's press indication).
                .indication(headerPressInteractions, LocalIndication.current)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = { offset ->
                            val press = PressInteraction.Press(offset)
                            headerPressInteractions.emit(press)
                            val released = tryAwaitRelease()
                            headerPressInteractions.emit(
                                if (released) PressInteraction.Release(press)
                                else PressInteraction.Cancel(press),
                            )
                        },
                        onTap = { onToggle() },
                        onLongPress = { offset ->
                            // detectTapGestures gives no haptic of its own —
                            // see the SessionRow note; fired by hand so the
                            // group header matches every other long-press menu.
                            headerHaptics.performHapticFeedback(
                                androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                            )
                            pressOffset = with(density) {
                                DpOffset(offset.x.toDp(), offset.y.toDp())
                            }
                            menuAlignEnd = offset.x > size.width / 2f
                            menuOpen = true
                        },
                    )
                }
                .semantics(mergeDescendants = true) {
                    onClick(label = expandLabel) { onToggle(); true }
                }
                .padding(horizontal = 10.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FolderComposedIcon(category = block.firstCategory)
            Spacer(Modifier.width(8.dp))
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // User data — rendered verbatim, never a string lookup.
                Text(
                    block.folder.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // totalCount, not ids.size — a collapsed group renders no
                    // rows but must still report its real membership. iOS
                    // summary line: "N chats · <newest member title>".
                    when {
                        block.totalCount > 0 && block.summaryTitle != null ->
                            stringResource(R.string.group_n_chats, block.totalCount) +
                                " · " + block.summaryTitle
                        block.totalCount > 0 ->
                            stringResource(R.string.group_n_chats, block.totalCount)
                        else -> stringResource(R.string.group_empty)
                    },
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    dateText,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.outline,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (block.folder.isPinned) {
                        Icon(
                            Icons.Default.PushPin,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(12.dp),
                        )
                    }
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier
                            .size(18.dp)
                            .rotate(chevronRotation),
                    )
                }
            }
        }
        // Invisible zero-size anchor at the press position — the menu opens
        // from the finger, right-edge-anchored when the press was on the
        // card's right half (see menuAlignEnd above).
        Box(
            modifier = Modifier
                .offset(x = pressOffset.x, y = pressOffset.y)
                .size(1.dp),
        ) {
            MinisMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                alignEnd = menuAlignEnd,
            ) {
                // [T-android-folder-menu-icons] One icon FAMILY and one frame
                // for every item: Outlined variants in a 20dp box. The old mix
                // (filled PushPin / filled Edit / filled FolderOff at default
                // 24dp) had three different visual weights and optical sizes
                // in a four-item menu.
                val menuIcon: @Composable (androidx.compose.ui.graphics.vector.ImageVector) -> Unit =
                    { image ->
                        Icon(
                            image,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (block.folder.isPinned) R.string.sessionlist_unpin
                                else R.string.sessionlist_pin,
                            ),
                        )
                    },
                    onClick = { menuOpen = false; onTogglePin() },
                    leadingIcon = { menuIcon(Icons.Outlined.PushPin) },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.group_rename)) },
                    onClick = { menuOpen = false; onRename() },
                    leadingIcon = { menuIcon(Icons.Outlined.Edit) },
                )
                // iOS folder menu parity: "New Chat in Group" (plus.bubble)
                // sits between Rename and the divider.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.group_new_chat_in)) },
                    onClick = { menuOpen = false; onNewChatInGroup() },
                    leadingIcon = { menuIcon(Icons.Outlined.AddComment) },
                )
                MinisMenuDivider()
                // Dissolve is deliberately NOT destructive-tinted (iOS note):
                // it touches no user data — sessions move back to the main
                // list. Tinting it red would train the eye to read it as the
                // deleting item.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.group_dissolve)) },
                    onClick = { menuOpen = false; onDissolve() },
                    leadingIcon = { menuIcon(Icons.Outlined.FolderOff) },
                )
                MinisMenuDivider()
                // The one destructive item, last, with the count in the title
                // so the consequence is visible in the menu itself, not only
                // in the confirmation dialog (iOS parity).
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                R.string.group_delete_with_sessions, block.totalCount,
                            ),
                            color = MaterialTheme.colorScheme.error,
                        )
                    },
                    onClick = { menuOpen = false; onDeleteWithSessions() },
                    leadingIcon = {
                        Icon(
                            Icons.Outlined.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                )
            }
        }
    }
}

// ─── Session Row (matching iOS SessionRow) ──────────────────────────────────

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SessionRow(
    session: ChatSessionEntity,
    onClick: () -> Unit,
    onLongClick: ((androidx.compose.ui.geometry.Offset) -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    searchQuery: String = "",
    searchSnippet: String? = null,
    /** See SessionItemContent — Transparent inside a folder container. */
    rowBackground: Color? = null,
) {
    val style = remember(session.category) { categoryStyle(session.category) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val timeText = remember(session.updatedAt, ctx) { relativeDate(ctx, session.updatedAt) }
    val rowHaptics = androidx.compose.ui.platform.LocalHapticFeedback.current

    // [T-android-sessionrow-press-indication] The long-press path uses raw
    // detectTapGestures (it needs the press OFFSET to anchor the context
    // menu), which — unlike clickable — carries no indication, so rows gave
    // zero visual feedback on tap/long-press. Drive the standard ripple by
    // hand: emit Press/Release/Cancel into an InteractionSource from
    // onPress, and mount it with Modifier.indication.
    //
    // Highlight SHAPE mirrors the folder card (user request): ungrouped rows
    // clip the indication to the same 6dp-inset, 16dp-radius rounded rect the
    // group card uses — 6dp outside the clip + 10dp inside keeps the total
    // 16dp content lead, so nothing moves. Folder members skip this: their
    // wrapper Box already clips to the welded container's segment shape
    // (square middles / bottom-rounded last), and a rounded ripple mid-weld
    // would break the one-container illusion.
    val pressInteractions = remember { MutableInteractionSource() }
    val inFolder = rowBackground != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBackground ?: MaterialTheme.colorScheme.surface)
            .then(
                if (!inFolder) {
                    Modifier
                        .padding(horizontal = 6.dp)
                        .clip(RoundedCornerShape(16.dp))
                } else {
                    Modifier
                }
            )
            .then(
                if (onLongClick != null) {
                    Modifier
                        .indication(pressInteractions, LocalIndication.current)
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onPress = { offset ->
                                    val press = PressInteraction.Press(offset)
                                    pressInteractions.emit(press)
                                    val released = tryAwaitRelease()
                                    pressInteractions.emit(
                                        if (released) PressInteraction.Release(press)
                                        else PressInteraction.Cancel(press),
                                    )
                                },
                                onTap = {
                                    com.openminis.app.diagnostics.PerfLongCtx.click(session.id)
                                    onClick()
                                },
                                onLongPress = { offset ->
                                    // Same reason the ripple is driven by hand
                                    // above: raw detectTapGestures carries no
                                    // built-in feedback, so the haptic that
                                    // `combinedClickable` gives for free has to
                                    // be fired explicitly here.
                                    rowHaptics.performHapticFeedback(
                                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress
                                    )
                                    onLongClick(offset)
                                },
                            )
                        }
                } else {
                    Modifier.clickable {
                        com.openminis.app.diagnostics.PerfLongCtx.click(session.id)
                        onClick()
                    }
                }
            )
            .padding(
                // 10dp in BOTH branches. Ungrouped: 6dp highlight-clip inset
                // + 10 = 16dp lead. In-folder: the wrapper Box already adds
                // the container's 6dp inset, so 16dp here pushed member icons
                // to 22dp — 6dp right of the folder card's own icon (6 outer
                // + 10 inner = 16). 10dp restores one shared 16dp icon grid
                // for the card, its members, and ungrouped rows alike.
                horizontal = 10.dp,
                vertical = 12.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (leadingIcon != null) {
            leadingIcon()
        }

        // Category icon in colored circle (18% opacity matching iOS)
        val activeSessions by SessionActivityTracker.activeSessions.collectAsState()
        val isActive = session.id in activeSessions
        // [T-android-session-paused-badge] Head of this session's badge queue
        // — null for the common case. Renders as an overlay in the icon's
        // bottom-right corner, mirroring where iOS's iCloud badge sits so
        // future ICLOUD_SYNCING uses the same anchor.
        val badgeMap by com.openminis.app.service.SessionBadgeStore.byId.collectAsState()
        val badgeHead = badgeMap[session.id]?.firstOrNull()
        Box(
            modifier = Modifier.size(44.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(
                        color = style.color.copy(alpha = 0.22f),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = style.icon,
                    contentDescription = null,
                    tint = style.color,
                    modifier = Modifier.size(22.dp),
                )
            }
            if (isActive) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .align(Alignment.Center),
                    contentAlignment = Alignment.Center,
                ) {
                    SpinningRing(
                        color = style.color,
                        modifier = Modifier.size(42.dp),
                    )
                }
            }
            if (badgeHead != null) {
                SessionBadgeOverlay(
                    state = badgeHead,
                    modifier = Modifier.align(Alignment.BottomEnd),
                )
            }
        }

        // Title + last message (or highlighted snippet during search)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            val titleText = session.title ?: stringResource(R.string.new_chat)
            if (searchQuery.isNotBlank()) {
                Text(
                    text = highlightedAnnotatedString(titleText, searchQuery),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = titleText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // During an active search, prefer the matched message snippet
            // (content hit) over the generic lastMessage preview. Falls back
            // to lastMessage when match was title-only (snippet is null).
            if (searchQuery.isNotBlank() && searchSnippet != null) {
                Text(
                    text = highlightedAnnotatedString(searchSnippet, searchQuery),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Text(
                    text = session.lastMessage ?: stringResource(R.string.session_list_no_messages),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Relative timestamp
        Text(
            text = timeText,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.78f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Spinning arc overlaid on the session icon while the agent loop is active.
 * Mirrors iOS `SpinningRing` (ContentView.swift:2405): 1.5dp stroke at 30%
 * opacity, 30% arc length, full rotation every ~1 second. Uses
 * `withFrameNanos` instead of an `animate*` API so recomposition across
 * onAppear calls does not stack multiple rotation animations.
 */
@Composable
internal fun SpinningRing(
    color: Color,
    modifier: Modifier = Modifier,
) {
    var angle by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val startNanos = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                val elapsedSec = (now - startNanos) / 1_000_000_000f
                angle = (elapsedSec * 360f) % 360f
            }
        }
    }
    Canvas(modifier = modifier.rotate(angle)) {
        val stroke = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round)
        drawArc(
            color = color.copy(alpha = 0.8f),
            startAngle = 0f,
            sweepAngle = 360f * 0.3f,
            useCenter = false,
            size = Size(size.width, size.height),
            style = stroke,
        )
    }
}

/**
 * [T-android-session-paused-badge] Corner overlay for [SessionBadgeStore]
 * states. Anchored bottom-end inside the 44dp icon Box. Mirrors where the
 * iOS iCloud-sync badge sits so future ICLOUD_SYNCING uses the same anchor.
 *
 * Sizing: 14dp circle, ~2/3 the size of the 20dp category icon — visible
 * but doesn't overwhelm the icon glyph. Translated 2dp down/right so the
 * badge sits *on* the icon edge instead of flush with the row padding
 * (matches the visual weight of iOS's badge offset).
 */
@Composable
internal fun SessionBadgeOverlay(
    state: com.openminis.app.service.SessionBadgeStore.SessionBadgeState,
    modifier: Modifier = Modifier,
) {
    when (state) {
        com.openminis.app.service.SessionBadgeStore.SessionBadgeState.PAUSED -> {
            Box(
                modifier = modifier
                    .offset(x = 2.dp, y = 2.dp)
                    .size(14.dp)
                    .background(
                        // Solid system-orange. Picked over yellow so the
                        // alert reads as "attention" rather than "info".
                        color = ChatColors.warn,
                        shape = CircleShape,
                    )
                    .border(
                        width = 1.5.dp,
                        color = MaterialTheme.colorScheme.surface,
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                // Pause glyph (⏸) — mirrors iOS's "pause.fill" badge so the
                // cross-platform "this task was paused" affordance matches.
                Icon(
                    imageVector = Icons.Filled.Pause,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(9.dp),
                )
            }
        }
        com.openminis.app.service.SessionBadgeStore.SessionBadgeState.ICLOUD_SYNCING -> {
            // Reserved for the upcoming iCloud-equivalent sync surface;
            // not produced yet. Render nothing rather than a placeholder
            // so a stray persisted entry from a future build doesn't
            // surface a debug-looking icon on the current build.
        }
    }
}

// ─── Onboarding Landing (iOS-style 3-step setup) ───────────────────────────

@Composable
internal fun OnboardingLanding(
    hasProviders: Boolean,
    hasMainSlot: Boolean,
    onAddProvider: () -> Unit,
    onSelectModels: () -> Unit,
    onStartConversation: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Bottom padding ≈ top-bar height so the content visually centers
            // relative to the whole screen, not just the Scaffold inner area.
            .padding(horizontal = 32.dp)
            .padding(bottom = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.AutoAwesome,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.sessionlist_welcome_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.sessionlist_welcome_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(32.dp))

        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            SetupStepCard(
                number = 1,
                title = stringResource(R.string.sessionlist_welcome_step1_title),
                subtitle = if (hasProviders) {
                    stringResource(R.string.sessionlist_welcome_step_done)
                } else {
                    stringResource(R.string.sessionlist_welcome_step1_subtitle)
                },
                isDone = hasProviders,
                isLocked = false,
                onClick = { if (!hasProviders) onAddProvider() },
            )

            SetupStepCard(
                number = 2,
                title = stringResource(R.string.sessionlist_welcome_step2_title),
                subtitle = when {
                    hasMainSlot -> stringResource(R.string.sessionlist_welcome_step_done)
                    hasProviders -> stringResource(R.string.sessionlist_welcome_step2_subtitle)
                    else -> stringResource(R.string.sessionlist_welcome_step2_locked)
                },
                isDone = hasMainSlot,
                isLocked = !hasProviders,
                onClick = { if (hasProviders && !hasMainSlot) onSelectModels() },
            )

            SetupStepCard(
                number = 3,
                title = stringResource(R.string.sessionlist_welcome_step3_title),
                subtitle = if (hasMainSlot) {
                    stringResource(R.string.sessionlist_welcome_step3_subtitle)
                } else {
                    stringResource(R.string.sessionlist_welcome_step3_locked)
                },
                isDone = false,
                isLocked = !hasMainSlot,
                onClick = { if (hasMainSlot) onStartConversation() },
            )
        }
    }
}

@Composable
internal fun SetupStepCard(
    number: Int,
    title: String,
    subtitle: String,
    isDone: Boolean,
    isLocked: Boolean,
    onClick: () -> Unit,
) {
    val isEnabled = !isDone && !isLocked

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(enabled = isEnabled, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(
                    color = if (isDone) ChatColors.ok else MaterialTheme.colorScheme.primary,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (isDone) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            } else {
                Text(
                    text = "$number",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        Column(
            modifier = Modifier.weight(1f).height(56.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = if (isDone) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (!isDone && isEnabled) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        }
    }
}

// ─── Edit Title & Category Sheet (matching iOS SessionEditSheet) ──────────

internal val allCategories = listOf(
    "Code", "Writing", "Research", "Analysis",
    "Creative", "Chat", "Math", "Translation",
    "Health", "Finance", "Travel", "Education",
    "Design", "Productivity", "Support", "Other",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SessionEditSheet(
    session: ChatSessionEntity,
    onDismiss: () -> Unit,
    onSave: (title: String, category: String?) -> Unit,
    // [T-android-sessionedit-regenerate-button] Regenerate-Title support,
    // matching iOS SessionEditSheet. `liveSession` is the DB-backed row that
    // updates when regeneration writes a new title/category; `isRegenerating`
    // drives the button's loading/disabled state; `onRegenerate` reuses the
    // existing SessionListViewModel.regenerateTitle logic. Defaults make the
    // button a no-op when a caller doesn't wire them up.
    liveSession: ChatSessionEntity = session,
    isRegenerating: Boolean = false,
    onRegenerate: () -> Unit = {},
) {
    var title by remember { mutableStateOf(session.title ?: "") }
    var selectedCategory by remember { mutableStateOf(session.category) }

    // [T-android-sessionedit-regenerate-button] When a regeneration run writes a
    // new title/category to the DB, `liveSession` updates — mirror those values
    // into the sheet's local edit state so the Title field and Category grid
    // refresh in place (iOS reads the fresh ChatStore session on completion).
    // A field the user has already edited keeps their text: only a field still equal to the
    // last value synced from the DB follows the regenerated one.
    var syncedTitle by remember { mutableStateOf(session.title ?: "") }
    var syncedCategory by remember { mutableStateOf(session.category) }
    LaunchedEffect(liveSession.title, liveSession.category) {
        liveSession.title?.let { fresh ->
            if (title == syncedTitle) title = fresh
            syncedTitle = fresh
        }
        if (selectedCategory == syncedCategory) selectedCategory = liveSession.category
        syncedCategory = liveSession.category
    }

    MinisModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = if (LocalUiStyle.current == UiStyle.GLASS) Color.Transparent else minisSheetColor(),
    ) {
        GlassSheetWindowBlur()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .glassSheetSurface()
                .padding(horizontal = 16.dp)
                .padding(bottom = 32.dp)
                .navigationBarsPadding(),
        ) {
            // Title bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MinisTextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Spacer(Modifier.weight(1f))
                Text(
                    stringResource(R.string.session_edit_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.weight(1f))
                val defaultTitle = stringResource(R.string.new_chat)
                MinisTextButton(
                    onClick = { onSave(title.ifBlank { defaultTitle }, selectedCategory) },
                ) { Text(stringResource(R.string.save)) }
            }

            Spacer(Modifier.height(16.dp))

            // Title field
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.webapp_sheet_title_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )

            Spacer(Modifier.height(20.dp))

            Text(
                "Category",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            // Category grid (4 columns, matching iOS LazyVGrid)
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.height(240.dp),
            ) {
                items(allCategories) { cat ->
                    val isSelected = selectedCategory?.equals(cat, ignoreCase = true) == true
                    val style = categoryStyle(cat.lowercase())
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isSelected) style.color.copy(alpha = 0.2f)
                                else MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                            .clickable {
                                selectedCategory = if (isSelected) null else cat.lowercase()
                            }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                imageVector = style.icon,
                                contentDescription = null,
                                tint = style.color,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                cat,
                                fontSize = 11.sp,
                                color = if (isSelected) style.color
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // [T-android-sessionedit-regenerate-button] Regenerate Title —
            // matches iOS SessionEditSheet's dedicated section below Category.
            // Reuses SessionListViewModel.regenerateTitle; shows a spinner and
            // disables while running (regeneratingIds) to prevent double taps.
            MinisOutlinedButton(
                onClick = onRegenerate,
                enabled = !isRegenerating,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isRegenerating) {
                    androidx.compose.material3.CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sessionlist_regenerating_title))
                } else {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sessionlist_regenerate_title))
                }
            }
        }
    }
}

// ─── Export Session ────────────────────────────────────────────────────────

/**
 * Long-chat export (T-export-optimize b443b54d, iOS sister c9d1087d).
 *
 * Pre-fix: this loaded every [MessageEntity] for the session at once,
 * built the whole JSON / TXT payload in memory, and shoved it into
 * [Intent.EXTRA_TEXT]. Hundreds of messages caused jank, "ghost" frames
 * and OOM crashes — see linked feedback.
 *
 * Now: hand off to [com.openminis.app.share.ChatExporter] which paginates
 * (50 rows / batch) on [kotlinx.coroutines.Dispatchers.IO], streams to a
 * staging file under `cacheDir/export-staging/`, then zips into
 * `cacheDir/shared/` and hands the resulting [android.net.Uri] to the
 * share sheet as a real file attachment. Peak memory stays bounded by
 * batch size regardless of session length.
 */
/** Exports every session in [ids] as its own zip and offers them together in one share sheet. */
internal fun exportSessions(
    context: Context,
    ids: List<String>,
    chatRepository: ChatRepository,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    if (ids.isEmpty()) return
    scope.launch {
        try {
            val uris = ArrayList<android.net.Uri>()
            for (id in ids) {
                val session = chatRepository.getSession(id) ?: continue
                uris += com.openminis.app.share.ChatExporter.exportToZip(
                    context = context,
                    session = session,
                    repository = chatRepository,
                    format = "markdown",
                ).first
            }
            if (uris.isEmpty()) return@launch
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = "application/zip"
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(intent, context.getString(R.string.sessionlist_export))
                    .apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) },
            )
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.export_progress_failed),
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }
}

internal fun exportSession(
    context: Context,
    session: ChatSessionEntity,
    chatRepository: ChatRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    format: String,
) {
    scope.launch {
        try {
            val (uri, _) = com.openminis.app.share.ChatExporter.exportToZip(
                context = context,
                session = session,
                repository = chatRepository,
                format = format,
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_SUBJECT, session.title ?: "Conversation")
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(
                intent,
                context.getString(R.string.sessionlist_export),
            ).apply { addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            context.startActivity(chooser)
        } catch (t: Throwable) {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.export_progress_failed),
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }
}
