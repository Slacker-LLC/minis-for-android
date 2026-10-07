package com.openminis.app.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.key
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.flow.first

// Helper: extract shell command from args or content (mirrors iOS toolDescription logic).
// Also tolerant of *partial* streaming JSON (JSONObject.optString returns "" for
// truncated objects, so fall back to a streaming-safe substring scan).
internal fun extractShellCommand(args: org.json.JSONObject, block: AssistantBlock): String {
    // 1. Try toolArgs "command" field (works once JSON is complete)
    val fromArgs = args.optString("command", "")
    if (fromArgs.isNotEmpty()) return fromArgs
    // 2. Streaming fallback: scan the raw toolArgs buffer for `"command":"…`
    //    which may not yet close. Mirrors iOS extractPartialStringValue.
    val partial = extractPartialJsonString("command", block.toolArgs)
    if (!partial.isNullOrEmpty()) return partial
    // 3. Try parsing "$ <command>" from first line of content (iOS fallback)
    if (block.content.startsWith("$ ")) {
        val firstLine = block.content.lineSequence().firstOrNull() ?: ""
        if (firstLine.length > 2) return firstLine.drop(2)
    }
    // 4. iOS fallback: generic "Shell command"
    return "Shell command"
}

/**
 * Tolerant partial-JSON string extractor — UI-side mirror of
 * ChatViewModel.extractPartialStringValue. Used so detail-sheet renderers
 * (shell command, file path, write content) can show live content while the
 * model is still streaming the tool input JSON.
 */
internal fun extractPartialJsonString(key: String, json: String): String? {
    if (json.isEmpty()) return null
    val patterns = listOf("\"$key\": \"", "\"$key\":\"")
    for (p in patterns) {
        val at = json.indexOf(p)
        if (at < 0) continue
        val after = json.substring(at + p.length)
        var i = 0
        val n = after.length
        while (i < n) {
            val c = after[i]
            if (c == '\\') { i += 2; continue }
            if (c == '"') {
                return after.substring(0, i)
                    .replace("\\n", "\n").replace("\\t", "\t")
                    .replace("\\\"", "\"").replace("\\/", "/")
                    .replace("\\\\", "\\")
            }
            i++
        }
        // No closing quote yet (still streaming) — return what we have.
        return after.replace("\\n", "\n").replace("\\t", "\t")
            .replace("\\\"", "\"").replace("\\/", "/")
            .replace("\\\\", "\\")
    }
    return null
}

/**
 * Shared editor-card layout used by file_read / file_write / memory_* detail
 * views. Mirrors iOS `fileEditorContent` + `memoryEditorContent` from
 * ToolLiveSheet.swift: an inner card with:
 *  - 10dp rounded corners + 0.5dp outline
 *  - header row (icon + title + size label) on a slightly darker strip
 *  - divider
 *  - monospaced body padded to 14dp (horizontal) / 14dp (vertical)
 *  - optional trailing footer text below the card
 *
 * Colors are parameterized so callers can tint the whole card (e.g. memory
 * uses pink everywhere; file uses neutral primary text).
 */

// ─── [T-android-tool-result-lazy-render] ────────────────────────────────────
// Opening a tool-result detail (ToolDetailSheet) with a large payload — a big
// memory_get / file read — janked: the body was a single Text laid out at once
// inside a verticalScroll Column (which, like a ScrollView, does NOT virtualize),
// so the whole 70KB+ string was composed + measured on open. Mirrors iOS
// commit 9d81ba18 (ToolLiveSheet lazy-reveal).
//
// We chunk the body into 40-line groups and reveal an initial window (~200
// lines / 10KB, whichever is fewer), growing the window each time the user
// reaches the bottom (auto-bump on the footer's onGloballyPositioned) or taps
// "Load more" / "Load all". Only the revealed Texts are composed, so open is
// cheap regardless of total size. Still scrollable and still selectable —
// rendered inside the caller's SelectionContainer; the reveal window's Texts
// register with the same plain-Compose SelectionRegistrar (NOT MinisTextKit,
// which is the chat-list markdown layer and isn't involved here).

internal const val LAZY_TOOL_CHUNK_LINES = 40

internal const val LAZY_TOOL_INITIAL_CHUNKS = 5            // ~200 lines

internal const val LAZY_TOOL_BATCH_CHUNKS = 5              // +200 lines per reveal

internal const val LAZY_TOOL_INITIAL_BYTE_CAP = 10 * 1024 // clamp first window to ~10KB

/** Split [text] into ordered 40-line chunks. Each chunk keeps its trailing
 *  newline so concatenation round-trips. */
internal fun chunkToolOutput(text: String): List<String> {
    if (text.isEmpty()) return emptyList()
    val lines = text.split("\n")
    val out = ArrayList<String>((lines.size / LAZY_TOOL_CHUNK_LINES) + 1)
    var i = 0
    while (i < lines.size) {
        val end = minOf(i + LAZY_TOOL_CHUNK_LINES, lines.size)
        // Re-join with "\n"; the final chunk omits the trailing separator that
        // split() consumed, which is fine — we never re-concatenate for display.
        out.add(lines.subList(i, end).joinToString("\n"))
        i = end
    }
    return out
}

/** Initial reveal count: up to [LAZY_TOOL_INITIAL_CHUNKS], further clamped so
 *  the first window stays under [LAZY_TOOL_INITIAL_BYTE_CAP] (covers a few very
 *  long lines that fit in < 5 chunks but exceed 10KB). */
internal fun initialRevealChunks(chunks: List<String>): Int {
    if (chunks.isEmpty()) return 0
    var count = 0
    var bytes = 0
    for (chunk in chunks.take(LAZY_TOOL_INITIAL_CHUNKS)) {
        bytes += chunk.toByteArray(Charsets.UTF_8).size
        count++
        if (bytes >= LAZY_TOOL_INITIAL_BYTE_CAP) break
    }
    return count.coerceAtLeast(1)
}

/**
 * [T-android-tool-result-lazy-render] Render [bodyText] with incremental reveal.
 * Builds the revealed prefix as ONE AnnotatedString (optionally linkified) so
 * selection/copy spans the whole revealed window as a single contiguous Text,
 * then a "Load more / Load all" footer. Caller supplies the surrounding
 * verticalScroll (passed in as [scrollState]) + SelectionContainer.
 *
 * Auto-reveal is gated on the scroll position reaching the bottom — NOT on the
 * footer being positioned. The body lives inside a `verticalScroll` Column,
 * which composes + positions ALL children (it doesn't virtualize), so a
 * position-based trigger would fire for the off-screen footer immediately and
 * reveal everything at once, defeating the laziness. Watching
 * `scrollState.value` vs `maxValue` instead only grows the window once the user
 * has actually scrolled near the end of what's currently revealed.
 */
@Composable
internal fun LazyRevealToolText(
    bodyText: String,
    color: Color,
    scrollState: androidx.compose.foundation.ScrollState,
    modifier: Modifier = Modifier,
    linkify: Boolean = false,
) {
    val chunks = remember(bodyText) { chunkToolOutput(bodyText) }
    // Reset the reveal window whenever the underlying text changes (e.g. the
    // user pages to a different tool block, which swaps bodyText).
    var revealed by remember(bodyText) { mutableStateOf(initialRevealChunks(chunks)) }
    val total = chunks.size
    val shownText = remember(bodyText, revealed) {
        chunks.take(revealed.coerceIn(1, total.coerceAtLeast(1))).joinToString("\n")
    }
    val urlClick = LocalMarkdownUrlClickHandler.current
    val displayed = remember(shownText, urlClick, linkify) {
        if (linkify && urlClick != null) {
            com.openminis.app.ui.util.linkifyUrls(text = shownText, onClick = urlClick)
        } else androidx.compose.ui.text.AnnotatedString(shownText)
    }

    // Auto-grow the window when the user scrolls within ~600px of the bottom of
    // the currently-revealed content. derivedStateOf keeps the predicate from
    // recomposing on every scroll pixel; it only flips at the threshold.
    val nearBottom by remember {
        derivedStateOf {
            val max = scrollState.maxValue
            max > 0 && max != Int.MAX_VALUE && scrollState.value >= max - 600
        }
    }
    LaunchedEffect(nearBottom, revealed, total) {
        if (nearBottom && revealed < total) {
            revealed = (revealed + LAZY_TOOL_BATCH_CHUNKS).coerceAtMost(total)
        }
    }

    Column(modifier = modifier) {
        Text(
            text = displayed,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace,
            color = color,
            lineHeight = 18.sp,
            modifier = Modifier.fillMaxWidth(),
        )
        if (revealed < total) {
            val remainingChunks = total - revealed
            val nextLines = minOf(LAZY_TOOL_BATCH_CHUNKS, remainingChunks) * LAZY_TOOL_CHUNK_LINES
            val remainingLines = remainingChunks * LAZY_TOOL_CHUNK_LINES
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.tool_load_more_lines, nextLines),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = color.copy(alpha = 0.9f),
                    modifier = Modifier.clickable {
                        revealed = (revealed + LAZY_TOOL_BATCH_CHUNKS).coerceAtMost(total)
                    },
                )
                Text(
                    text = stringResource(R.string.tool_load_all_lines, remainingLines),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = color.copy(alpha = 0.9f),
                    modifier = Modifier.clickable { revealed = total },
                )
            }
        }
    }
}

@Composable
internal fun EditorCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    iconTint: Color,
    titleColor: Color,
    sizeColor: Color,
    bodyText: String,
    bodyColor: Color,
    isStreaming: Boolean,
    scrollState: androidx.compose.foundation.ScrollState,
    trailingText: String? = null,
) {
    val bytes = bodyText.toByteArray(Charsets.UTF_8).size
    val sizeLabel = when {
        bodyText.isEmpty() -> null
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
    // Match iOS fileEditorContent (ToolLiveSheet.swift:1350) instead of the
    // generic chat palette: the editor card needs its own dark/light grayscale
    // ramp so the body stands out from the sheet container and the header
    // strip reads as one notch lighter than the body.
    //   iOS dark  : body white:0.10 (#1A1A1A), header white:0.13 (#212121),
    //               border white:0.25 (#404040)
    //   iOS light : body white:0.94 (#F0F0F0), header white:0.92 (#EBEBEB),
    //               border white:0.82 (#D1D1D1)
    // T126-fix: use ChatPalette.isDark so the in-app theme override (Settings →
    // Appearance) wins over the system setting. Otherwise users on Light system
    // + Dark in-app would see white card on black chat.
    val isDark = ChatColors.isDark
    val cardBg = if (isDark) Color(0xFF1A1A1A) else Color(0xFFF2F2F7)
    val headerBg = if (isDark) Color(0xFF212121) else Color(0xFFF2F2F7)
    val cardBorder = if (isDark) Color(0xFF404040) else Color(0xFFD1D1D1)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(cardBg)
                .border(0.5.dp, cardBorder, RoundedCornerShape(10.dp)),
        ) {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerBg)
                    .padding(vertical = 10.dp, horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(14.dp))
                Text(
                    text = title,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = titleColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (sizeLabel != null) {
                    Text(
                        text = if (isStreaming) stringResource(R.string.tool_size_received, sizeLabel) else "($sizeLabel)",
                        fontSize = 11.sp,
                        color = if (isStreaming) ChatColors.warn.copy(alpha = 0.8f) else sizeColor,
                    )
                }
            }
            HorizontalDivider(thickness = 0.5.dp, color = cardBorder)

            // Body
            if (bodyText.isNotEmpty()) {
                // T193 (supersedes T191): EditorCard renders both inline in
                // the chat LazyColumn (wrapped in SelectionContainer at L1519)
                // AND inside the ToolLiveSheet ModalBottomSheet — two
                // independent Compose subtrees. T191 used DisableSelection to
                // dodge the cross-tree `findCommonAncestor` crash but lost
                // the ability to select text. A nested SelectionContainer
                // creates its own SelectionRegistrar; Texts inside it register
                // there instead of the outer chat-list registrar, so the
                // selection toolbar's coordinate walk stays within this
                // subtree and never tries to span two hierarchies — both
                // instances are crash-safe and individually selectable.
                androidx.compose.foundation.text.selection.SelectionContainer {
                    // [T-android-tool-result-lazy-render] Reveal large bodies
                    // incrementally instead of laying the whole string out on
                    // open (a 70KB memory_get janked the sheet for seconds).
                    LazyRevealToolText(
                        bodyText = bodyText,
                        color = bodyColor,
                        scrollState = scrollState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                    )
                }
            } else if (isStreaming) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = iconTint,
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
        if (trailingText != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = trailingText,
                fontSize = 12.sp,
                color = ChatColors.tertiaryText,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}
