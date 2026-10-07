package com.openminis.app.ui.sessions

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Brush
import androidx.compose.material.icons.outlined.Calculate
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChecklistRtl
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Group
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.FolderEntity
import com.openminis.app.ui.components.MinisMenu
import com.openminis.app.ui.components.MinisMenuDivider
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.glass.glassSurface
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.ui.theme.LocalUiStyle
import com.openminis.app.ui.theme.UiStyle
import java.util.Calendar
import java.util.Date
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map

// FAB color — use shared theme values

internal data class CategoryStyle(val icon: ImageVector, val color: Color)

// 16 categories matching iOS (ContentView.swift:1897-1916)
internal fun categoryStyle(category: String?): CategoryStyle {
    return when (category?.lowercase()) {
        "code"         -> CategoryStyle(Icons.Outlined.Code, Color(0xFFF09A37))
        "writing"      -> CategoryStyle(Icons.Outlined.Description, Color(0xFF3478F6))
        "research"     -> CategoryStyle(Icons.Outlined.Language, Color(0xFF30B0C7))
        "analysis"     -> CategoryStyle(Icons.Outlined.BarChart, Color(0xFF5856D6))
        "creative"     -> CategoryStyle(Icons.Outlined.Brush, Color(0xFFFF2D55))
        "chat"         -> CategoryStyle(Icons.Outlined.Forum, Color(0xFF34C759))
        "math"         -> CategoryStyle(Icons.Outlined.Calculate, Color(0xFF9B59B6))
        "translation"  -> CategoryStyle(Icons.Outlined.Translate, Color(0xFF00BCD4))
        "health"       -> CategoryStyle(Icons.Outlined.Favorite, Color(0xFFFF3B30))
        "finance"      -> CategoryStyle(Icons.Outlined.Payments, Color(0xFF00C7BE))
        "travel"       -> CategoryStyle(Icons.Outlined.Map, Color(0xFFF09A37))
        "education"    -> CategoryStyle(Icons.Outlined.Book, Color(0xFF3478F6))
        "design"       -> CategoryStyle(Icons.Outlined.Palette, Color(0xFFFF2D55))
        "productivity" -> CategoryStyle(Icons.Outlined.CalendarMonth, Color(0xFFFFCC00))
        "support"      -> CategoryStyle(Icons.Outlined.Settings, Color(0xFF8B6914))
        "other"        -> CategoryStyle(Icons.Outlined.GridView, Color(0xFF8E8E93))
        else           -> CategoryStyle(Icons.Outlined.Forum, Color(0xFF8E8E93))
    }
}

// Date period for section grouping (matching iOS)
internal enum class DatePeriod(val label: String) {
    PINNED("Pinned"),     // labels are i18n'd at render time via sectionLabelFor
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    THIS_WEEK("This Week"),
    THIS_MONTH("This Month"),
    EARLIER("Earlier"),
}

/**
 * Map a session's updatedAt timestamp to its display bucket. Mirrors iOS
 * `ContentView.groupedSessions`:
 *   - Today / Yesterday: calendar-day match
 *   - This Week: within the last 7 days (rolling window, not current week)
 *   - This Month: within the last 30 days (rolling window, NOT current calendar month)
 *   - Earlier: everything else
 */
internal fun datePeriod(timestamp: Long): DatePeriod {
    val now = Calendar.getInstance()
    val cal = Calendar.getInstance().apply { time = Date(timestamp) }

    if (cal.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
        cal.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
    ) return DatePeriod.TODAY

    val yesterday = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (cal.get(Calendar.YEAR) == yesterday.get(Calendar.YEAR) &&
        cal.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR)
    ) return DatePeriod.YESTERDAY

    val diffDays = TimeUnit.MILLISECONDS.toDays(now.timeInMillis - timestamp)
    if (diffDays < 7) return DatePeriod.THIS_WEEK

    // iOS uses `monthAgo = now - 1 month` (rolling window). A calendar-month
    // match would push e.g. a March 30 session into "Earlier" on April 2 —
    // iOS still shows it in "This Month" until May 2.
    val monthAgo = Calendar.getInstance().apply { add(Calendar.MONTH, -1) }.timeInMillis
    if (timestamp > monthAgo) return DatePeriod.THIS_MONTH

    return DatePeriod.EARLIER
}

/**
 * [T-android-session-grouping] One rendered group block: a user-created group
 * plus the sessions filed into it.
 *
 * Holds session IDS, not session objects — the list differ re-evaluates this on
 * every emission, so the value must stay cheap to compare. (iOS learned the
 * same lesson as `SidebarGroup`; a `List<ChatSessionEntity>` here deep-compares
 * long message strings on every tick.)
 *
 * `ids` is EMPTY while collapsed, but [totalCount] keeps the real number so the
 * card can still say "5 chats".
 */
internal const val GROUP_BADGE_FRESH_WINDOW_MS = 24L * 60L * 60L * 1000L

data class FolderGroupBlock(
    val folder: FolderEntity,
    val ids: List<String>,
    val totalCount: Int,
    val isCollapsed: Boolean,
    val latestUpdatedAt: Long,
    /** Newest member's title — iOS folderSectionHeader's "N chats · title" summary line. */
    val summaryTitle: String? = null,
    /** Newest member's category — tints the composed folder icon like iOS FolderComposedIcon. */
    val firstCategory: String? = null,
    val anyPaused: Boolean = false,
    val anyActive: Boolean = false,
)

/**
 * [T-android-session-grouping] Partition sessions into group blocks + the
 * ungrouped remainder.
 *
 * Ordering rules, ported from iOS `computeGroupedSessionIDs`:
 *  - Input arrives `updated_at DESC`, so first-encounter order over the filed
 *    sessions IS the groups' activity order — no separate sort needed.
 *  - Pinned groups float above unpinned as a STABLE PARTITION, not a re-sort,
 *    so activity order survives inside each half.
 *  - **Group membership outranks pin for PLACEMENT**: a pinned session that is
 *    also filed renders inside its group, not in the Pinned bucket. Otherwise
 *    filing a pinned session looks like a no-op — the write lands but the row
 *    never moves. The pin itself is untouched: pinned members sort first inside
 *    the group and keep their pin glyph.
 *  - A `folder_id` pointing at a group we don't have renders as UNGROUPED
 *    rather than vanishing. There is no FK, so this is a normal state.
 *  - Empty groups still render — a group that disappears when its last session
 *    moves out reads as data loss.
 */
internal fun partitionByFolder(
    sessions: List<ChatSessionEntity>,
    folders: List<FolderEntity>,
    collapsedIds: Set<String>,
    freshBadgedIds: Set<String> = emptySet(),
    activeSessionIds: Set<String> = emptySet(),
): Pair<List<FolderGroupBlock>, List<ChatSessionEntity>> {
    if (folders.isEmpty()) return emptyList<FolderGroupBlock>() to sessions

    val byId = folders.associateBy { it.id }
    val members = LinkedHashMap<String, MutableList<ChatSessionEntity>>()
    val ungrouped = mutableListOf<ChatSessionEntity>()

    for (s in sessions) {
        val fid = s.folderId
        // Presence check against the loaded map — never a DB constraint.
        if (fid != null && byId.containsKey(fid)) {
            members.getOrPut(fid) { mutableListOf() }.add(s)
        } else {
            ungrouped.add(s)
        }
    }

    // First-encounter order = activity order. Groups with no members are
    // appended afterwards so they still render.
    val ordered = members.keys.toMutableList()
    for (f in folders) if (f.id !in members) ordered.add(f.id)

    val openId = ordered.firstOrNull { it !in collapsedIds }

    val blocks = ordered.mapNotNull { fid ->
        val folder = byId[fid] ?: return@mapNotNull null
        val m = members[fid].orEmpty()
        val collapsed = fid != openId
        // Pinned members first, stable partition — the pin is a display
        // affordance inside the group, not a reason to leave it.
        val displayOrdered = m.filter { it.pinnedAt != null } + m.filter { it.pinnedAt == null }
        FolderGroupBlock(
            folder = folder,
            ids = if (collapsed) emptyList() else displayOrdered.map { it.id },
            totalCount = m.size,
            isCollapsed = collapsed,
            // Recency order (not display order) — this means "newest activity".
            latestUpdatedAt = m.firstOrNull()?.updatedAt ?: folder.updatedAt,
            summaryTitle = m.firstOrNull()?.title,
            firstCategory = m.firstOrNull()?.category,
            anyPaused = freshBadgedIds.isNotEmpty() && m.any { it.id in freshBadgedIds },
            anyActive = activeSessionIds.isNotEmpty() && m.any { it.id in activeSessionIds },
        )
    }

    val pinnedFirst = blocks.filter { it.folder.isPinned } + blocks.filter { !it.folder.isPinned }
    return pinnedFirst to ungrouped
}

internal fun groupSessionsByDate(sessions: List<ChatSessionEntity>): List<Pair<DatePeriod, List<ChatSessionEntity>>> {
    val pinned = sessions.filter { it.pinnedAt != null }.sortedByDescending { it.pinnedAt }
    val unpinned = sessions.filter { it.pinnedAt == null }
    val grouped = unpinned.groupBy { datePeriod(it.updatedAt) }
    val result = mutableListOf<Pair<DatePeriod, List<ChatSessionEntity>>>()
    if (pinned.isNotEmpty()) {
        result.add(DatePeriod.PINNED to pinned)
    }
    for (period in DatePeriod.entries) {
        if (period == DatePeriod.PINNED) continue
        grouped[period]?.let { result.add(period to it) }
    }
    return result
}

internal fun relativeDate(context: Context, timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val seconds = TimeUnit.MILLISECONDS.toSeconds(diff)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)

    if (seconds < 60) return context.getString(R.string.time_just_now)
    if (minutes < 60) return context.getString(R.string.time_minutes_ago, minutes.toInt())
    if (hours < 24) return context.getString(R.string.time_hours_ago, hours.toInt())

    val dateCal = Calendar.getInstance().apply { time = Date(timestamp) }
    val yesterdayCal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (dateCal.get(Calendar.YEAR) == yesterdayCal.get(Calendar.YEAR) &&
        dateCal.get(Calendar.DAY_OF_YEAR) == yesterdayCal.get(Calendar.DAY_OF_YEAR)
    ) {
        return context.getString(R.string.time_yesterday)
    }

    val days = TimeUnit.MILLISECONDS.toDays(diff)
    if (days < 7) {
        // T172: device-locale weekday names via java.text.DateFormatSymbols.
        val dayNames = java.text.DateFormatSymbols(java.util.Locale.getDefault()).weekdays
        return dayNames[dateCal.get(Calendar.DAY_OF_WEEK)]
    }

    val month = dateCal.get(Calendar.MONTH) + 1
    val day = dateCal.get(Calendar.DAY_OF_MONTH)
    return "$month/$day"
}

// ─── Dual FAB Row (matching iOS fabRow) ─────────────────────────────────────

/** Persisted preference key for FAB order swap. */
internal const val PREF_FAB_SWAPPED = "fab_swapped"

@Composable
internal fun DualFabRow(
    isDark: Boolean,
    isSearchActive: Boolean,
    searchQuery: String,
    isSearching: Boolean,
    hasSessions: Boolean,
    onNewChat: () -> Unit,
    onSearchToggle: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSearchDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE) }
    var isSwapped by remember { mutableStateOf(prefs.getBoolean(PREF_FAB_SWAPPED, false)) }

    // T120: focus + IME control for the inline search field. The field appears
    // inside an AnimatedVisibility, so we drive focus from the parent and
    // request it when isSearchActive flips true. Showing the keyboard
    // explicitly via the SoftwareKeyboardController covers devices where
    // requestFocus() alone doesn't trigger the IME (e.g. some Pixel + Gboard
    // combinations under edge-to-edge layouts).
    val searchFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current
    LaunchedEffect(isSearchActive) {
        if (isSearchActive) {
            // AnimatedVisibility runs a 200ms enter animation; the TextField
            // isn't attached to the composition tree until the first frame of
            // that animation lands. Yield once so requestFocus() targets a
            // composed node rather than throwing IllegalStateException.
            kotlinx.coroutines.delay(50)
            runCatching { searchFocusRequester.requestFocus() }
            keyboardController?.show()
        }
    }

    // Drag offset for the currently-dragged FAB
    var chatDragX by remember { mutableFloatStateOf(0f) }
    var searchDragX by remember { mutableFloatStateOf(0f) }

    // Threshold to trigger swap (half screen width roughly)
    val density = LocalDensity.current
    val swapThreshold = with(density) { 100.dp.toPx() }

    val chatFab: @Composable () -> Unit = {
        val isGlass = LocalUiStyle.current == UiStyle.GLASS
        val primary = MaterialTheme.colorScheme.primary
        // Pick black or white for at least 4.5:1 contrast on the opaque CTA.
        val fabContentColor = if (primary.luminance() > 0.179f) Color.Black else Color.White
        Box(
            modifier = Modifier
                .offset { IntOffset(chatDragX.roundToInt(), 0) }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (kotlin.math.abs(chatDragX) > swapThreshold) {
                                isSwapped = !isSwapped
                                prefs.edit().putBoolean(PREF_FAB_SWAPPED, isSwapped).apply()
                            }
                            chatDragX = 0f
                        },
                        onDragCancel = { chatDragX = 0f },
                        onHorizontalDrag = { _, dragAmount -> chatDragX += dragAmount },
                    )
                },
        ) {
            FloatingActionButton(
                onClick = onNewChat,
                shape = CircleShape,
                containerColor = if (isGlass) Color.Transparent else primary,
                modifier = Modifier
                    .size(60.dp)
                    .then(
                        if (isGlass) Modifier.glassSurface(
                            shape = CircleShape,
                            // Keep this primary action opaque so its icon remains legible.
                            glassScrim = primary,
                            fallbackScrim = primary,
                        ) else Modifier.shadow(8.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.2f)),
                    ),
                elevation = if (isGlass) {
                    FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp)
                } else {
                    FloatingActionButtonDefaults.elevation(defaultElevation = 6.dp)
                },
            ) {
                Icon(
                    Icons.Outlined.AddComment,
                    contentDescription = stringResource(R.string.new_chat),
                    tint = fabContentColor,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }

    val searchFab: @Composable () -> Unit = {
        val isGlass = LocalUiStyle.current == UiStyle.GLASS
        if (hasSessions) {
            AnimatedVisibility(
                visible = !isSearchActive,
                enter = fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.85f),
                exit = fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.85f),
            ) {
                FloatingActionButton(
                    onClick = onSearchToggle,
                    shape = CircleShape,
                    // iOS: UIColor.secondarySystemBackground = #F2F2F7 (light) / #1C1C1E (dark).
                    // ChatColors.secondaryBg already matches these values across themes.
                    containerColor = if (isGlass) Color.Transparent else ChatColors.secondaryBg,
                    modifier = Modifier
                        .size(56.dp)
                        .offset { IntOffset(searchDragX.roundToInt(), 0) }
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures(
                                onDragEnd = {
                                    if (kotlin.math.abs(searchDragX) > swapThreshold) {
                                        isSwapped = !isSwapped
                                        prefs.edit().putBoolean(PREF_FAB_SWAPPED, isSwapped).apply()
                                    }
                                    searchDragX = 0f
                                },
                                onDragCancel = { searchDragX = 0f },
                                onHorizontalDrag = { _, dragAmount -> searchDragX += dragAmount },
                            )
                        }
                        .then(
                            if (isGlass) Modifier.glassSurface(
                                shape = CircleShape,
                                glassScrim = if (isDark) Color.Black.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.5f),
                                fallbackScrim = ChatColors.secondaryBg,
                            ) else Modifier.shadow(6.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.15f)),
                        ),
                    elevation = if (isGlass) {
                        FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp)
                    } else {
                        FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp)
                    },
                ) {
                    Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.sessionlist_search_action), tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
                }
            }
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            // T24: lift the FAB+search row above the IME so the text field
            // remains visible while typing. Compose-managed inset — handles
            // the IME open/close animation in lockstep.
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Render in swapped or normal order
        if (isSwapped) { searchFab(); } else { chatFab() }

        // Middle: Inline search bar (when active)
        AnimatedVisibility(
            visible = isSearchActive,
            enter = fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.85f),
            exit = fadeOut(tween(150)) + scaleOut(tween(150), targetScale = 0.85f),
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.search_chats_placeholder)) },
                leadingIcon = {
                    Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                },
                trailingIcon = {
                    // T46: while debounce is in flight, swap the close icon
                    // for an indeterminate progress ring so the user sees the
                    // search is working — avoids the stale-results-then-snap
                    // transition on slow stores. Snaps back to the close
                    // button as soon as results land.
                    if (isSearching) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 12.dp)
                                .size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    } else {
                        IconButton(onClick = onSearchDismiss) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.sessionlist_dismiss), modifier = Modifier.size(18.dp))
                        }
                    }
                },
                // T46: full-capsule shape mirrors iOS searchable-field style
                // (see ContentView.fabRow — `.clipShape(Capsule())` over a
                // 56pt-tall HStack). RoundedCornerShape(50) is Compose's
                // canonical "pill" radius — guaranteed circular ends at any
                // height. Pair with a fixed 48dp height so the field aligns
                // with the flanking 56dp FABs without overpowering them.
                shape = androidx.compose.foundation.shape.RoundedCornerShape(percent = 50),
                // T10: Material3's default OutlinedTextField containerColor is
                // Color.Transparent, which lets the LazyColumn's session rows
                // bleed through and overlap the typed query text. Set both
                // focused and unfocused container colors to surfaceContainerHigh
                // (matches the grouped-section card background already used
                // throughout settings) so the field reads as a discrete
                // surface above the list. Also drop both border colors —
                // capsule shape with no outline reads more like iOS's filled
                // search bar than the M3 outlined field default.
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    focusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.8f),
                    unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                ),
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
                    // [T-android-search-height] Left at the component's own
                    // height. Forcing 42dp here clipped the placeholder: a
                    // plain OutlinedTextField keeps its 16dp vertical
                    // contentPadding no matter what the outer frame says, so
                    // shrinking the frame cuts the text. The model picker's
                    // field was rebuilt on BasicTextField + DecorationBox to
                    // get around that; this one is a simpler inline field and
                    // is not worth the same surgery for a few dp.
                    .heightIn(min = 48.dp)
                    .focusRequester(searchFocusRequester),
            )
        }

        if (isSwapped) { chatFab() } else { searchFab() }
    }
}

// ─── Selection Toolbar (matching iOS selectionToolbar) ──────────────────────

@Composable
internal fun SelectionToolbar(
    selectedCount: Int,
    onExport: () -> Unit,
    /** [T-android-session-grouping] Bulk-file the selection into a group. */
    onMove: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f))
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        // Export button (matching iOS)
        MinisTextButton(
            onClick = onExport,
            enabled = selectedCount > 0,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Share,
                    contentDescription = stringResource(R.string.sessionlist_export),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.sessionlist_export), fontSize = 11.sp)
            }
        }

        // Move to Group button
        MinisTextButton(
            onClick = onMove,
            enabled = selectedCount > 0,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Folder,
                    contentDescription = stringResource(R.string.group_move_action),
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.group_move_action), fontSize = 11.sp)
            }
        }

        // Delete button (matching iOS)
        MinisTextButton(
            onClick = onDelete,
            enabled = selectedCount > 0,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = stringResource(R.string.delete),
                    tint = if (selectedCount > 0) MaterialTheme.colorScheme.error else Color.Gray,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.delete),
                    fontSize = 11.sp,
                    color = if (selectedCount > 0) MaterialTheme.colorScheme.error else Color.Gray,
                )
            }
        }
    }
}

// ─── Section Header (matching iOS .subheadline.weight(.semibold)) ───────────

@Composable
internal fun SectionHeader(title: String) {
    // T172: title may now be a localized string, so compare against the
    // localized "Pinned" rather than the hardcoded enum label.
    val isPinned = title == stringResource(R.string.sessionlist_section_pinned)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .padding(top = 8.dp),
    ) {
        if (isPinned) {
            Icon(
                imageVector = Icons.Default.PushPin,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .size(14.dp)
                    .padding(end = 0.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = title,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ─── Session Item (context menu replaces swipe-to-delete, matching iOS) ─────

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SessionItemContent(
    session: ChatSessionEntity,
    isSelecting: Boolean,
    selectedIds: Set<String>,
    onSessionClick: (String) -> Unit,
    onToggleSelect: (String) -> Unit,
    // [T-android-sessionlist-longpress-select] Context-menu Select: enters
    // selection mode with this row selected (distinct from onToggleSelect,
    // which only flips set membership while ALREADY selecting).
    onEnterSelect: (String) -> Unit,
    onPinToggle: (String) -> Unit,
    onEditRequest: (ChatSessionEntity) -> Unit,
    onExportRequest: (ChatSessionEntity, String) -> Unit,
    /** [T-eta-character-cards] Opens the character picker for this session. */
    onCharacterRequest: (ChatSessionEntity) -> Unit,
    onRegenerateTitle: (String) -> Unit,
    onDuplicate: (String) -> Unit,
    onDeleteRequest: (String) -> Unit,
    /** [T-android-session-grouping] Opens the group picker for this session. */
    onMoveToGroup: (String) -> Unit,
    /**
     * [T-android-session-grouping] True only when this session belongs to a group
     * that ACTUALLY EXISTS locally — not merely `folderId != null`.
     *
     * A dangling folder_id renders as ungrouped (see partitionByFolder), so
     * deciding the wording from the raw id alone made the row and its menu
     * disagree: the session sat in the date buckets while its menu offered
     * "更换分组". The caller resolves membership the same way the list does.
     */
    isFiled: Boolean,
    isRegenerating: Boolean = false,
    searchQuery: String = "",
    searchSnippet: String? = null,
    /**
     * [T-android-folder-card-ios-parity] Overrides the row's own surface
     * background. Folder members pass Transparent so the group container's
     * welded fill shows through; null keeps the default surface.
     */
    rowBackground: Color? = null,
) {
    if (isSelecting) {
        val isSelected = session.id in selectedIds
        SessionRow(
            session = session,
            onClick = { onToggleSelect(session.id) },
            onLongClick = null,
            searchQuery = searchQuery,
            searchSnippet = searchSnippet,
            rowBackground = rowBackground,
            leadingIcon = {
                Icon(
                    imageVector = if (isSelected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(24.dp),
                )
            },
        )
    } else {
        var showContextMenu by remember { mutableStateOf(false) }
        var pressOffset by remember { mutableStateOf(DpOffset.Zero) }
        // [T-android-menu-press-side] Which HALF of the row the finger was on.
        // Pressing on the right used to left-anchor the menu at the finger,
        // overflow the window, and get clamped left — so the popup (and its
        // top-LEFT-origin scale animation) visually appeared to the left of
        // the finger. Right-half presses now anchor the menu's RIGHT edge at
        // the press point with a matching top-right animation origin, so the
        // menu hangs off the finger naturally on both sides.
        var menuAlignEnd by remember { mutableStateOf(false) }
        var rowWidthPx by remember { mutableFloatStateOf(0f) }
        val density = LocalDensity.current
        val isPinned = session.pinnedAt != null

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { rowWidthPx = it.width.toFloat() },
        ) {
            SessionRow(
                session = session,
                onClick = { onSessionClick(session.id) },
                searchQuery = searchQuery,
                searchSnippet = searchSnippet,
                rowBackground = rowBackground,
                onLongClick = { offsetPx ->
                    pressOffset = with(density) {
                        DpOffset(offsetPx.x.toDp(), offsetPx.y.toDp())
                    }
                    menuAlignEnd = rowWidthPx > 0f && offsetPx.x > rowWidthPx / 2f
                    showContextMenu = true
                },
            )
            // Loading overlay when regenerating title
            if (isRegenerating) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        androidx.compose.material3.CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            stringResource(R.string.sessionlist_regenerating_title),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            // Invisible zero-size anchor at the press position — DropdownMenu
            // will open from here so it follows the touch point.
            Box(
                modifier = Modifier
                    .offset(x = pressOffset.x, y = pressOffset.y)
                    .size(1.dp),
            ) {
                MinisMenu(
                    expanded = showContextMenu,
                    onDismissRequest = { showContextMenu = false },
                    alignEnd = menuAlignEnd,
                ) {
                // Pin / Unpin
                DropdownMenuItem(
                    text = { Text(stringResource(if (isPinned) R.string.sessionlist_unpin else R.string.sessionlist_pin)) },
                    onClick = {
                        showContextMenu = false
                        onPinToggle(session.id)
                    },
                    leadingIcon = {
                        Icon(
                            if (isPinned) Icons.Default.Close else Icons.Default.PushPin,
                            contentDescription = null,
                        )
                    },
                )
                // [T-eta-character-cards] Roleplay binding: pick the character this conversation
                // talks to, or clear it. The card snapshot the binding stores keeps the session
                // working even if the character is later deleted.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.characters_pick_title)) },
                    onClick = {
                        showContextMenu = false
                        onCharacterRequest(session)
                    },
                )
                // Export submenu (JSON / Plain Text)
                var showExportSub by remember { mutableStateOf(false) }
                DropdownMenuItem(
                    text = {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.sessionlist_export))
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    },
                    onClick = { showExportSub = !showExportSub },
                    leadingIcon = {
                        Icon(Icons.Default.Share, contentDescription = null)
                    },
                )
                if (showExportSub) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sessionlist_export_json), modifier = Modifier.padding(start = 24.dp)) },
                        onClick = {
                            showContextMenu = false
                            onExportRequest(session, "json")
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sessionlist_export_plain), modifier = Modifier.padding(start = 24.dp)) },
                        onClick = {
                            showContextMenu = false
                            onExportRequest(session, "text")
                        },
                    )
                    // [T-eta-conversation-export] Markdown transcript: headings, folded
                    // thinking and quoted tool activity, instead of the plain-text dump.
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.sessionlist_export_markdown), modifier = Modifier.padding(start = 24.dp)) },
                        onClick = {
                            showContextMenu = false
                            onExportRequest(session, "markdown")
                        },
                    )
                }
                // Edit Title & Category
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sessionlist_edit_title_category)) },
                    onClick = {
                        showContextMenu = false
                        onEditRequest(session)
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Edit, contentDescription = null)
                    },
                )
                // Regenerate Title
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sessionlist_regenerate_title)) },
                    onClick = {
                        showContextMenu = false
                        onRegenerateTitle(session.id)
                    },
                    leadingIcon = {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                    },
                )
                // Duplicate
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sessionlist_duplicate)) },
                    onClick = {
                        showContextMenu = false
                        onDuplicate(session.id)
                    },
                    leadingIcon = {
                        Icon(Icons.Default.ContentCopy, contentDescription = null)
                    },
                )
                // Move to / Change Group
                // [T-android-session-grouping] The wording follows membership:
                // a session already in a group is being MOVED BETWEEN groups,
                // not filed for the first time. Same idiom as Pin/Unpin.
                //
                // A single item opening a sheet, deliberately NOT an inline
                // submenu of group names — the menu body would then cost
                // O(groups) to compose on every open, and the group data would
                // have to be captured into the menu closure.
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(
                                if (isFiled) R.string.group_change
                                else R.string.group_move_to,
                            ),
                        )
                    },
                    onClick = {
                        showContextMenu = false
                        onMoveToGroup(session.id)
                    },
                    leadingIcon = {
                        Icon(
                            if (isFiled) Icons.Default.DriveFileMove
                            else Icons.Default.Folder,
                            contentDescription = null,
                        )
                    },
                )
                // Select
                // [T-android-sessionlist-longpress-select] Must ENTER
                // selection mode, not just toggle the hidden set —
                // onToggleSelect alone never set isSelecting, so nothing
                // visibly happened and the id sat invisibly pre-selected.
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sessionlist_select_action)) },
                    onClick = {
                        showContextMenu = false
                        onEnterSelect(session.id)
                    },
                    leadingIcon = {
                        Icon(Icons.Outlined.ChecklistRtl, contentDescription = null)
                    },
                )
                MinisMenuDivider()
                // Delete
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) },
                    onClick = {
                        showContextMenu = false
                        onDeleteRequest(session.id)
                    },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                        )
                    },
                )
                }
            }
        }
    }
}

/**
 * [T-android-session-grouping] The card that heads a group's block.
 *
 * Tapping it collapses/expands (accordion — opening one closes the others).
 * Long-pressing opens the management menu: pin, rename, dissolve. Dissolve is
 * deliberately NOT tinted destructive — it moves sessions back to the main list
 * and deletes nothing, and tinting it red would train the eye to read it as the
 * dangerous item.
 */
@OptIn(ExperimentalFoundationApi::class)
// ─── [T-android-folder-card-ios-parity] Folder container surface ────────────
//
// Port of iOS FolderSurface + FolderSegmentBorder (ContentView.swift): the
// folder group renders as ONE floating rounded container. Collapsed = a lone
// 16dp-radius card; expanded = the card becomes the TOP segment and each
// member row a MIDDLE/BOTTOM segment of the same surface, with the hairline
// border tiled around the outer perimeter only — no horizontal lines at row
// boundaries, so the pieces read as a single welded outline. Rows stay
// independent LazyColumn items (the iOS "never merge rows into one view"
// rule); only the background/border segmentation composes them.

internal enum class FolderSegment { LONE, TOP, MIDDLE, BOTTOM }

/**
 * Edge highlight for the folder container: bright hairline in dark mode, a
 * subtle dark line in light mode (white would vanish on the light page) —
 * iOS `folderEdgeHighlight` verbatim.
 */
@Composable
internal fun folderEdgeColor(): Color =
    if (ChatColors.isDark) Color.White.copy(alpha = 0.30f)
    else Color.Black.copy(alpha = 0.08f)

@Composable
internal fun folderFillColor(): Color = MaterialTheme.colorScheme.surfaceContainerLow

internal fun Modifier.folderSurface(
    segment: FolderSegment,
    fill: Color,
    edge: Color,
): Modifier = drawBehind {
    val r = 16.dp.toPx()
    val w = size.width
    val h = size.height
    val cr = CornerRadius(r, r)

    val fillPath = Path().apply {
        when (segment) {
            FolderSegment.LONE -> addRoundRect(RoundRect(0f, 0f, w, h, cr))
            FolderSegment.TOP -> addRoundRect(
                RoundRect(
                    rect = Rect(0f, 0f, w, h),
                    topLeft = cr, topRight = cr,
                    bottomLeft = CornerRadius.Zero, bottomRight = CornerRadius.Zero,
                ),
            )
            FolderSegment.MIDDLE -> addRect(Rect(0f, 0f, w, h))
            FolderSegment.BOTTOM -> addRoundRect(
                RoundRect(
                    rect = Rect(0f, 0f, w, h),
                    topLeft = CornerRadius.Zero, topRight = CornerRadius.Zero,
                    bottomLeft = cr, bottomRight = cr,
                ),
            )
        }
    }
    drawPath(fillPath, fill)

    // Border tiling (iOS FolderSegmentBorder): lone = full outline; top =
    // left edge up + top arcs + right edge down; middle = the two vertical
    // edges only; bottom = the mirror of top. Open paths — never a line
    // across a row boundary.
    val border = Path().apply {
        when (segment) {
            FolderSegment.LONE -> addRoundRect(RoundRect(0f, 0f, w, h, cr))
            FolderSegment.TOP -> {
                moveTo(0f, h)
                lineTo(0f, r)
                arcTo(Rect(0f, 0f, 2 * r, 2 * r), 180f, 90f, false)
                lineTo(w - r, 0f)
                arcTo(Rect(w - 2 * r, 0f, w, 2 * r), 270f, 90f, false)
                lineTo(w, h)
            }
            FolderSegment.MIDDLE -> {
                moveTo(0f, 0f); lineTo(0f, h)
                moveTo(w, 0f); lineTo(w, h)
            }
            FolderSegment.BOTTOM -> {
                moveTo(0f, 0f)
                lineTo(0f, h - r)
                arcTo(Rect(0f, h - 2 * r, 2 * r, h), 180f, -90f, false)
                lineTo(w - r, h)
                arcTo(Rect(w - 2 * r, h - 2 * r, w, h), 90f, -90f, false)
                lineTo(w, 0f)
            }
        }
    }
    drawPath(border, edge, style = Stroke(width = 0.75.dp.toPx()))
}

/**
 * Port of iOS GroupGlyphShape: the "grouped list" glyph — two rounded-square
 * rings on the left, four list lines on the right — traced from the same
 * 1024-unit SVG. Rings are even-odd so the whole glyph is a single fill.
 */
internal fun groupGlyphPath(side: Float): Path = Path().apply {
    fillType = PathFillType.EvenOdd
    val u = side / 1024f

    fun ring(x: Float, y: Float) {
        // Outer 325.8×325.8 with r 93; inner inset by the 46.5 stroke.
        val outer = Rect(x * u, y * u, (x + 325.8f) * u, (y + 325.8f) * u)
        addRoundRect(RoundRect(outer, CornerRadius(93f * u)))
        val inner = Rect(
            outer.left + 46.5f * u, outer.top + 46.5f * u,
            outer.right - 46.5f * u, outer.bottom - 46.5f * u,
        )
        addRoundRect(RoundRect(inner, CornerRadius(46.5f * u)))
    }

    fun line(cy: Float) {
        val rect = Rect(
            558.5f * u, (cy - 23.27f) * u,
            (558.5f + 325.8f) * u, (cy + 23.27f) * u,
        )
        addRoundRect(RoundRect(rect, CornerRadius(23.27f * u)))
    }

    ring(139.6f, 139.6f)
    ring(139.6f, 511.9f)
    line(209.5f)
    line(395.6f)
    line(581.8f)
    line(768.0f)
}

/**
 * Port of iOS FolderComposedIcon: the grouped-list glyph on the SAME circular
 * translucent tint the session rows use, at the same 44dp slot — a group icon
 * and a session icon are the same species at the same size. Tint borrows the
 * newest member's category color (gray when empty); 0.28 vs the session
 * icons' 0.18 so a group circle reads as a different kind of thing.
 */
@Composable
internal fun FolderComposedIcon(category: String?, diameter: Dp = 44.dp) {
    val tint = categoryStyle(category).color
    Box(
        modifier = Modifier
            .size(diameter)
            .background(tint.copy(alpha = 0.28f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(diameter * 0.56f)) {
            drawPath(groupGlyphPath(size.width), tint)
        }
    }
}
