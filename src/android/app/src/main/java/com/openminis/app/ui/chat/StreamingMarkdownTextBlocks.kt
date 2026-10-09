package com.openminis.app.ui.chat

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlayCircleFilled
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.openminis.app.R
import com.openminis.app.runtime.RuntimePathRegistry
import com.openminis.app.runtime.files.WorkspaceFileClient
import com.openminis.app.ui.DisplayBitmapLimits.limitDisplaySize
import com.openminis.app.ui.theme.ChatColors
import com.openminis.app.util.Sha256
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ─── Block renderers ────────────────────────────────────────────────────────

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun RenderBlock(block: MdBlock) {
    val colors = currentMdColors()
    // [T-android-streaming-incremental-inline] The live streaming tail block
    // re-parses its growing paragraph every throttle tick; route it through the
    // incremental cache (frozen closed prefix + fresh suffix). Frozen/history
    // blocks (false) keep the plain per-block cache — no behavior change there.
    val liveIncremental = LocalLiveIncremental.current
    when (block) {
        is MdBlock.Paragraph -> {
            MdText(
                text = if (liveIncremental) MarkdownParseCaches.inlineIncremental(block.raw, colors)
                       else MarkdownParseCaches.inline(block.raw, colors),
                fontSize = BaseFontSize,
                lineHeight = BaseLineHeight,
                color = colors.text,
                modifier = Modifier.padding(bottom = 4.dp),
                inlineContent = rememberKatexInlineContent(
                    BaseFontSize,
                    if (liveIncremental) MarkdownParseCaches.mathLatexIncremental(block.raw)
                    else MarkdownParseCaches.mathLatex(block.raw),
                ),
            )
        }

        is MdBlock.Heading -> {
            val (size, weight) = when (block.level) {
                1 -> (BaseFontSize * 1.5f) to FontWeight.Bold
                2 -> (BaseFontSize * 1.3f) to FontWeight.Bold
                3 -> (BaseFontSize * 1.15f) to FontWeight.SemiBold
                4 -> BaseFontSize to FontWeight.SemiBold
                5 -> (BaseFontSize * 0.875f) to FontWeight.SemiBold
                else -> (BaseFontSize * 0.85f) to FontWeight.SemiBold
            }
            MdText(
                text = MarkdownParseCaches.inline(block.text, colors),
                fontSize = size,
                fontWeight = weight,
                lineHeight = size * 1.3f,
                color = colors.text,
                modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                inlineContent = rememberKatexInlineContent(size, MarkdownParseCaches.mathLatex(block.text)),
            )
        }

        is MdBlock.CodeBlock -> {
            val clipboardManager = LocalClipboardManager.current
            var copied by remember { mutableStateOf(false) }
            if (copied) {
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(1500)
                    copied = false
                }
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.codeBg),
            ) {
                // Header row: language label + copy button
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 8.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = block.language.ifEmpty { "code" },
                        fontSize = 11.sp,
                        // The block's own palette: the label used to be white, which only reads on the dark
                        // code block and vanished on the light one.
                        color = ChatColors.secondaryText,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        imageVector = if (copied) com.openminis.app.ui.components.MinisIcons.Check else com.openminis.app.ui.components.MinisIcons.Copy,
                        contentDescription = if (copied) "Copied" else "Copy code",
                        tint = if (copied) ChatColors.ok else ChatColors.secondaryText,
                        modifier = Modifier
                            .size(16.dp)
                            .clickable {
                                clipboardManager.setText(androidx.compose.ui.text.AnnotatedString(block.code))
                                copied = true
                            },
                    )
                }
                // iOS parity (SelectableMarkdownView.swift L971): cap visual
                // code-block height at ~400 pt and let an internal scroll
                // view handle overflow vertically, so a 200-line dump
                // doesn't push the rest of the message off the bottom of
                // the chat. Nest scrolls: inner Row owns horizontal scroll
                // (long lines), outer Box owns vertical scroll + height
                // cap (long blocks). Compose disallows two scroll modifiers
                // on the same node, hence the nesting.
                val vScroll = rememberScrollState()
                val hScroll = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(vScroll)
                        .padding(bottom = 8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .horizontalScroll(hScroll)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = block.code,
                            fontSize = BaseFontSize * 0.85f,
                            fontFamily = FontFamily.Monospace,
                            color = colors.codeText,
                            lineHeight = BaseLineHeight * 0.9f,
                        )
                    }
                }
            }
        }

        is MdBlock.BlockQuote -> {
            // T307: previous IntrinsicSize.Min approach crashes when inner
            // blocks contain SubcomposeLayout (tables, images, etc.) — Compose
            // refuses intrinsic measurement on those. Draw the orange rule
            // directly behind a single Column so layout never queries
            // intrinsics.
            val barColor = ChatColors.warn
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .drawBehind {
                        drawRect(
                            color = barColor,
                            topLeft = Offset.Zero,
                            size = Size(3.dp.toPx(), size.height),
                        )
                    }
                    .padding(start = 15.dp),
            ) {
                block.innerBlocks.forEach { inner -> RenderBlock(inner) }
            }
        }

        is MdBlock.Details -> {
            var open by remember(block.raw) { mutableStateOf(false) }
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { open = !open }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (open) "\u25BE  " else "\u25B8  ",
                        fontSize = BaseFontSize,
                        color = ChatColors.secondaryText,
                    )
                    MdText(
                        text = MarkdownParseCaches.inline(block.summary, colors),
                        fontSize = BaseFontSize,
                        fontWeight = FontWeight.SemiBold,
                        lineHeight = BaseLineHeight,
                        color = colors.text,
                        modifier = Modifier.weight(1f),
                        inlineContent = rememberKatexInlineContent(BaseFontSize, MarkdownParseCaches.mathLatex(block.summary)),
                    )
                }
                if (open) {
                    Column(modifier = Modifier.padding(start = 18.dp)) {
                        block.inner.forEach { inner -> RenderBlock(inner) }
                    }
                }
            }
        }

        is MdBlock.UnorderedList -> {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                block.items.forEach { item ->
                    Row(modifier = Modifier.padding(start = 8.dp, bottom = 2.dp)) {
                        Text("•  ", fontSize = BaseFontSize * 1.3f, lineHeight = BaseLineHeight, color = colors.text)
                        MdText(
                            text = MarkdownParseCaches.inline(item.text, colors),
                            fontSize = BaseFontSize,
                            lineHeight = BaseLineHeight,
                            color = colors.text,
                            modifier = Modifier.weight(1f),
                            inlineContent = rememberKatexInlineContent(BaseFontSize, MarkdownParseCaches.mathLatex(item.text)),
                        )
                    }
                    item.children.forEach { child ->
                        Box(modifier = Modifier.padding(start = 26.dp)) { RenderBlock(child) }
                    }
                }
            }
        }

        is MdBlock.OrderedList -> {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                block.items.forEachIndexed { index, item ->
                    Row(modifier = Modifier.padding(start = 8.dp, bottom = 2.dp)) {
                        Text(
                            "${block.startNum + index}.  ",
                            fontSize = BaseFontSize,
                            lineHeight = BaseLineHeight,
                            color = colors.text,
                        )
                        MdText(
                            text = MarkdownParseCaches.inline(item.text, colors),
                            fontSize = BaseFontSize,
                            lineHeight = BaseLineHeight,
                            color = colors.text,
                            modifier = Modifier.weight(1f),
                            inlineContent = rememberKatexInlineContent(BaseFontSize, MarkdownParseCaches.mathLatex(item.text)),
                        )
                    }
                    item.children.forEach { child ->
                        Box(modifier = Modifier.padding(start = 26.dp)) { RenderBlock(child) }
                    }
                }
            }
        }

        is MdBlock.TaskList -> {
            Column(modifier = Modifier.padding(bottom = 8.dp)) {
                block.items.forEach { item ->
                    Row(
                        modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = item.checked,
                            onCheckedChange = null,
                            modifier = Modifier.size(20.dp),
                            colors = CheckboxDefaults.colors(
                                checkedColor = colors.link,
                            ),
                        )
                        Spacer(Modifier.width(6.dp))
                        MdText(
                            text = MarkdownParseCaches.inline(item.text, colors),
                            fontSize = BaseFontSize,
                            lineHeight = BaseLineHeight,
                            color = if (item.checked) colors.text.copy(alpha = 0.5f) else colors.text,
                            modifier = Modifier.weight(1f),
                            inlineContent = rememberKatexInlineContent(BaseFontSize, MarkdownParseCaches.mathLatex(item.text)),
                        )
                    }
                }
            }
        }

        is MdBlock.HorizontalRule -> {
            HorizontalDivider(
                color = colors.divider,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        is MdBlock.Image -> {
            android.util.Log.d("MdStream", "render Image url=${block.url}")
            // [T-android-markdown-image-gallery-cross-message] Prefer the
            // image-specific handler when provided so the host can collect
            // every sibling image across the conversation and open a paged
            // gallery (mirrors iOS AIChatView.handleMarkdownImageTap). Fall
            // back to the generic URL handler — which routes a single-item
            // open via ChatLinkResolver — when the host hasn't supplied an
            // image handler (keeps the previous behaviour intact).
            val imageTapHandler = LocalMarkdownImageTapHandler.current
            val urlTapHandler = LocalMarkdownUrlClickHandler.current
            val ambientMessageId = LocalShardId.current?.messageId
            val onTap: (() -> Unit)? = when {
                imageTapHandler != null && ambientMessageId != null ->
                    { -> imageTapHandler(ambientMessageId, block.url) }
                urlTapHandler != null -> { -> urlTapHandler(block.url) }
                else -> null
            }
            val context = LocalContext.current
            val sessionId = LocalMarkdownSessionId.current
            // Resolve to a host File via the session-scoped resolver before
            // handing off to Coil. The generic minis:// fetcher has no chat
            // identity, so a known-session miss must never fall through to it.
            val file = rememberMdMediaFile(block.url, sessionId)
            val imageData = remember(file, block.url, sessionId) {
                if (file != null) {
                    file
                } else if (sessionId != null && block.url.substringBefore('?').startsWith("minis://")) {
                    // A deterministic non-existent file makes Coil surface its
                    // error placeholder without probing another chat's mounts.
                    File(context.cacheDir, ".missing-minis-media/${block.url.hashCode()}")
                } else {
                    block.url
                }
            }
            // T146: 1dp hairline + 2dp soft shadow so a white-bg PNG (matplotlib
            // chart, screenshot…) reads as a discrete card against the chat
            // surface. Same ChatColors.thumbnailBorder / inputShadow recipe as
            // the attachment chip in T179 — keeps the visual rhythm consistent.
            // shadow → clip → border so the elevation paints behind the rounded
            // edge and the border stays crisp on top.
            QuoteFileHost(file, file?.name ?: block.alt.ifBlank { "image" }) { onLongClick ->
            val imageShape = RoundedCornerShape(8.dp)
            val imageBaseModifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .shadow(
                    elevation = 2.dp,
                    shape = imageShape,
                    clip = false,
                    ambientColor = ChatColors.inputShadow,
                    spotColor = ChatColors.inputShadow,
                )
                .clip(imageShape)
                .border(1.dp, ChatColors.thumbnailBorder, imageShape)
                .let { m -> if (onTap != null || onLongClick != null) m.combinedClickable(onClick = { onTap?.invoke() }, onLongClick = onLongClick) else m }
            // T148: SubcomposeAsyncImage so we can render a broken-image
            // placeholder when the underlying file is gone (deleted workspace
            // PNG, broken URL). Without this slot, Coil paints nothing and
            // the user sees a blank gap where a chart should be — easy to
            // mistake for a render bug.
            // [T-android-canvas-large-bitmap-crash] Cap the DECODE size.
            // Without an explicit request size, Coil sizes from the layout
            // constraints — but this column scrolls vertically, so the height
            // constraint is unbounded and Coil falls back to the image's
            // intrinsic size, decoding a very tall chart PNG at full
            // resolution. The resulting bitmap (215MB in the vivo/Android 16
            // report) exceeds RecordingCanvas's draw ceiling and crashes the
            // process from ThreadedRenderer.draw. Capping here means the
            // oversized bitmap is never allocated at all. FillWidth still
            // scales the (now bounded) bitmap to the column width, so normal
            // images render byte-identically to before.
            // Remembered per (file, url): this renderer recomposes on every
            // streaming token, and rebuilding the request each time would churn
            // allocations in a hot path.
            val imageRequest = remember(imageData) {
                ImageRequest.Builder(context)
                    .data(imageData)
                    .decoderFactory(
                        if (android.os.Build.VERSION.SDK_INT >= 28) {
                            ImageDecoderDecoder.Factory()
                        } else {
                            GifDecoder.Factory()
                        }
                    )
                    .limitDisplaySize()
                    .build()
            }
            SubcomposeAsyncImage(
                model = imageRequest,
                contentDescription = block.alt,
                modifier = imageBaseModifier,
                contentScale = ContentScale.FillWidth,
                // A big picture drawn small: mip-mapped sampling keeps fine text and lines readable.
                filterQuality = androidx.compose.ui.graphics.FilterQuality.Medium,
            ) {
                when (painter.state) {
                    is AsyncImagePainter.State.Error -> BrokenImagePlaceholder(alt = block.alt)
                    else -> SubcomposeAsyncImageContent()
                }
            }
            }
        }

        is MdBlock.Video -> {
            android.util.Log.d("MdStream", "render Video url=${block.url}")
            RenderMdVideo(block)
        }

        is MdBlock.Audio -> {
            android.util.Log.d("MdStream", "render Audio url=${block.url}")
            RenderMdAudio(block)
        }

        is MdBlock.Table -> {
            RenderTable(block)
        }

        is MdBlock.MathDisplay -> {
            RenderMathDisplay(block.latex)
        }

        is MdBlock.FileAttachment -> {
            android.util.Log.d("MdStream", "render FileAttachment title=${block.title} url=${block.url}")
            ChatFileAttachmentView(
                title = block.title,
                url = block.url,
            )
        }
    }
}

// ─── Math (KaTeX) ───────────────────────────────────────────────────────────

/**
 * T208-4 part 4: Per-latex InlineTextContent registry.
 *
 * Compose's Placeholder API requires a fixed size at construction — there
 * is no way to resize a placeholder after the inline text has been laid
 * out. The previous design used a single shared placeholder sized to
 * `fontSize * 6 × fontSize * 1.4` (≈ 96 × 22.5 dp at 16 sp); KaTeX
 * routinely produces ~26 dp tall bitmaps (subscript descenders), and any
 * formula wider than 96 dp simply did not fit. ContentScale.Fit then
 * shrank every formula to ~85 % to make it fit the slot, producing the
 * "everything looks shrunken" output the user reported in T208-4.
 *
 * The fix: each unique latex string registers its OWN InlineTextContent
 * with its own placeholder, sized via `estimateInlineMathSize` based on
 * the latex's character count and structural triggers. Compose draws the
 * KaTeX bitmap at its natural dp size centered inside that slot — no
 * shrink, no clip. Extra padding inside an over-estimated slot is
 * harmless; under-estimating would re-introduce the shrink, so the
 * estimator is intentionally generous.
 *
 * The list of latex strings comes from `collectInlineMathLatex`, which
 * runs the same delimiter scanner as `parseInline` over the raw text.
 */
@Composable
internal fun rememberKatexInlineContent(
    fontSize: TextUnit,
    latexList: List<String>,
): Map<String, androidx.compose.foundation.text.InlineTextContent> {
    if (latexList.isEmpty()) return emptyMap()
    return remember(fontSize, latexList) {
        val map = HashMap<String, androidx.compose.foundation.text.InlineTextContent>(latexList.size)
        for (latex in latexList.toSet()) {
            val (w, h) = estimateInlineMathSize(latex, fontSize)
            map[katexInlineTagFor(latex)] = androidx.compose.foundation.text.InlineTextContent(
                placeholder = androidx.compose.ui.text.Placeholder(
                    width = w,
                    height = h,
                    // [T-android-math-baseline] TextCenter, was AboveBaseline.
                    // The slot is over-estimated AND the KaTeX bitmap carries
                    // its own top/bottom whitespace, so an above-baseline slot
                    // put the formula's optical center well ABOVE the line's
                    // ("N(100) 渲染偏上"). Centering the slot on the line and
                    // the bitmap in the slot (CenterStart below) aligns the
                    // two optical centers instead — robust against both the
                    // generous estimate and the bitmap padding.
                    placeholderVerticalAlign = androidx.compose.ui.text.PlaceholderVerticalAlign.TextCenter,
                ),
            ) { _ ->
                // Compose passes the alternative-text to the children lambda;
                // we already keyed the slot per-latex so we use the closure's
                // `latex` directly to avoid any tag/text mismatch.
                RenderInlineMath(latex = latex, fontSize = fontSize)
            }
        }
        map
    }
}

@Composable
internal fun RenderInlineMath(latex: String, fontSize: TextUnit) {
    val context = LocalContext.current
    val isDark = ChatColors.isDark
    // T208-5: pass the sp value as CSS px so the rendered glyph height
    // matches the surrounding body text. KaTeX's HTML sets
    // `el.style.fontSize = fontSize + 'px'` and the WebView viewport runs
    // at initial-scale=1.0, so 1 CSS px = 1 dp. Passing 16 here makes the
    // formula glyphs 16 dp tall — same as the 16-sp Compose body text.
    // Earlier code passed sp.toPx() (= sp × density = 42 on a density-2.625
    // device), which produced a bitmap ~2.6× too large; combined with the
    // CSS-vs-physical-px snapshot bug it accidentally landed near correct
    // size, but with the snapshot bug fixed the inflation showed through.
    //
    // [T-android-math-fontscale] ×fontScale: 16 sp of TEXT draws at
    // 16 × fontScale dp when the SYSTEM font size setting isn't 100%, and
    // the inline Placeholder (TextUnit sp) scales with it — but the CSS-px
    // bitmap did NOT. On a small-font device (fontScale < 1) the bitmap
    // came out LARGER than the shrunken slot, and Compose clips inline
    // content to the placeholder bounds — the field report's "N(10(" (a
    // clipped N(100)) and formulas visibly oversized next to their own
    // paragraph text. fontScale > 1 gave the inverse: formulas too small.
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val fontSizeCssPx = (fontSize.value * fontScale).toInt().coerceAtLeast(12)
    val palette = currentMdColors()
    val result by androidx.compose.runtime.produceState<KatexRenderResult?>(
        initialValue = null,
        key1 = latex,
        key2 = isDark,
        key3 = fontSizeCssPx,
    ) {
        value = KatexWebViewPool.render(
            context = context,
            latex = latex,
            displayMode = false,
            isDark = isDark,
            fontSizePx = fontSizeCssPx,
        )
    }
    val rendered = result
    if (rendered != null) {
        // T208-4 part 4: the slot was sized by `estimateInlineMathSize`
        // generously enough for this latex, so draw the bitmap at its
        // natural dp size (no scaling, no shrinking). ContentScale.Fit
        // is the defensive fallback if the estimator ever under-shoots.
        val density = androidx.compose.ui.platform.LocalDensity.current.density
        val naturalWidthDp = (rendered.bitmap.width / density).dp
        val naturalHeightDp = (rendered.bitmap.height / density).dp
        // [T-android-math-fontscale] Defensive de-clip: the slot was sized by
        // estimateInlineMathSize, but any residual estimator drift (or a
        // future slot/bitmap unit mismatch) used to CLIP the formula — inline
        // content never exceeds its placeholder bounds. Measure the slot and
        // scale the bitmap DOWN to fit when needed: a slightly shrunken
        // formula is readable, a clipped one ("N(10(") is not.
        //
        // [T-android-math-baseline] BOTTOM-align the bitmap. The placeholder
        // uses AboveBaseline (slot bottom sits ON the text baseline), but its
        // height is deliberately over-estimated — with the default TopStart
        // alignment the formula rode at the TOP of the too-tall slot,
        // floating visibly above its own line ("N(100) 渲染偏上"). Anchoring
        // to the slot's bottom puts the formula on the baseline regardless of
        // how generous the height estimate is.
        androidx.compose.foundation.layout.BoxWithConstraints(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.CenterStart,
        ) {
            val fit = minOf(
                1f,
                if (naturalWidthDp > maxWidth) maxWidth / naturalWidthDp else 1f,
                if (naturalHeightDp > maxHeight) maxHeight / naturalHeightDp else 1f,
            )
            androidx.compose.foundation.Image(
                bitmap = rendered.bitmap.asImageBitmap(),
                contentDescription = "math: $latex",
                modifier = Modifier.size(naturalWidthDp * fit, naturalHeightDp * fit),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
        }
    } else {
        // Fallback while loading or on error: show the raw latex so the
        // user is never staring at an empty rectangle.
        Text(
            text = latex,
            fontSize = fontSize * 0.9f,
            fontFamily = FontFamily.Monospace,
            color = palette.text,
        )
    }
}

/**
 * T155: Display-mode math rendered via the shared KaTeX WebView pool.
 * Shows the bitmap snapshot once KaTeX returns; falls back to monospace
 * raw LaTeX while loading or on render error so the user always sees
 * *something* meaningful even before / instead of the rendered formula.
 *
 * iOS parity: KaTeXRenderer.swift (single offscreen WKWebView, snapshot,
 * cached). The render call is suspending — Compose drives it via
 * `produceState` keyed by (latex, isDark, fontSize) so flipping themes
 * or scrolling back-and-forth never re-renders the same formula twice.
 */
@Composable
internal fun RenderMathDisplay(latex: String) {
    val context = LocalContext.current
    val isDark = ChatColors.isDark
    // T208-5: render at sp.value (CSS px = dp) so glyph height matches the
    // surrounding 16-sp body text. See RenderInlineMath comment for the
    // full reasoning. [T-android-math-fontscale] ×fontScale so display math
    // tracks the SYSTEM font size setting the way the surrounding sp text
    // does (the inline path had the same gap — see RenderInlineMath).
    val displayFontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    val fontSizeCssPx = (BaseFontSize.value * displayFontScale).toInt().coerceAtLeast(12)
    val palette = currentMdColors()

    val result by androidx.compose.runtime.produceState<KatexRenderResult?>(
        initialValue = null,
        key1 = latex,
        key2 = isDark,
        key3 = fontSizeCssPx,
    ) {
        value = KatexWebViewPool.render(
            context = context,
            latex = latex,
            displayMode = true,
            isDark = isDark,
            fontSizePx = fontSizeCssPx,
        )
    }

    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        val rendered = result
        if (rendered != null) {
            val density = androidx.compose.ui.platform.LocalDensity.current.density
            val naturalWidthDp = (rendered.bitmap.width / density).dp
            val naturalHeightDp = (rendered.bitmap.height / density).dp
            val scale = if (naturalWidthDp > maxWidth) maxWidth / naturalWidthDp else 1f
            androidx.compose.foundation.Image(
                bitmap = rendered.bitmap.asImageBitmap(),
                contentDescription = "math: $latex",
                modifier = Modifier.size(naturalWidthDp * scale, naturalHeightDp * scale),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
        } else {
            // Fallback: raw latex in monospace inside a faint surface.
            Text(
                text = latex,
                fontSize = BaseFontSize * 0.95f,
                fontFamily = FontFamily.Monospace,
                color = palette.text,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

// ─── Broken image placeholder ───────────────────────────────────────────────

/**
 * T148: Visible fallback for `SubcomposeAsyncImage` error state inside
 * markdown — rendered when Coil can't load the source (file deleted,
 * 404, IO error). Without this the slot paints nothing and the user
 * can't tell whether the image is missing or whether the renderer is
 * broken. The outer `imageBaseModifier` already supplies the T146
 * border/shadow/rounded-corner frame; here we just fill the inside
 * with a subtle tool-bg, a broken-image glyph, and the alt text.
 */
@Composable
internal fun BrokenImagePlaceholder(alt: String?) {
    val palette = com.openminis.app.ui.theme.LocalChatPalette.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(4f / 3f)
            .background(palette.toolBg),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.BrokenImage,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = palette.secondaryText,
            )
            Text(
                text = alt?.takeIf { it.isNotBlank() } ?: "Image not available",
                fontSize = 12.sp,
                color = palette.secondaryText,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ─── Media helpers ──────────────────────────────────────────────────────────

/**
 * Resolve a markdown media URL (`minis://attachments/foo.mp4`, file://, or
 * plain absolute path) to a local File. Canonical guest files are staged from
 * the guest file API; SAF mounts and rootfs files retain their Android-side File mapping.
 *
 * A caller with a session id resolves only within that session. Sessionless
 * callers can resolve global guest roots but never search another session.
 */
internal fun resolveMdMediaFile(context: Context, url: String, sessionId: String? = null): File? {
    if (url.isBlank()) return null
    // Strip a real query (`?`), but NOT `#` — attachment filenames legitimately
    // contain '#' (hashtags). `minis://` URLs don't carry fragments anyway,
    // and truncating here would hide the '.mp4' extension and the file's real
    // name from the resolver.
    val stripped = url.substringBefore('?')
    val primary: File? = when {
        stripped.startsWith("minis://") -> {
            val decoded = java.net.URLDecoder.decode(stripped.removePrefix("minis://"), "UTF-8")
            val linuxPath = if (decoded.startsWith('/')) decoded else "/var/minis/$decoded"
            // Prefer the session-scoped resolver when the caller supplied a
            // sessionId: the global `bindMounts` map is overwritten every time
            // another session boots its shell, so without sessionId we'd route
            // this chat's attachment lookup to whichever session happened to
            // boot last.
            if (linuxPath == "/var/minis/mounts" || linuxPath.startsWith("/var/minis/mounts/")) {
                RuntimePathRegistry.resolveHostPath(linuxPath)
            } else {
                stageGuestMedia(context, linuxPath, sessionId)
            }
        }
        stripped.startsWith("file://") -> File(Uri.parse(stripped).path ?: return null)
        stripped == "/var/minis/mounts" || stripped.startsWith("/var/minis/mounts/") ->
            RuntimePathRegistry.resolveHostPath(stripped)
        stripped.startsWith("/var/minis") || stripped.startsWith("/workspace") ||
            stripped.startsWith("/memory") || stripped.startsWith("/skills") ||
            stripped.startsWith("/shared") || stripped.startsWith("/home/minis") ->
            stageGuestMedia(context, stripped, sessionId)
        stripped.startsWith("/") -> File(stripped)
        else -> null
    }
    if (primary?.let { it.exists() && it.isFile } == true) {
        android.util.Log.d("MdStream", "resolveMdMediaFile url=$url sid=$sessionId -> primary=${primary.absolutePath}")
        return primary
    }

    android.util.Log.w("MdStream", "resolveMdMediaFile url=$url -> NOT FOUND (primary=${primary?.absolutePath})")
    return null
}

@Composable
internal fun rememberMdMediaFile(url: String, sessionId: String?): File? {
    val context = LocalContext.current
    val file by produceState<File?>(initialValue = null, key1 = url, key2 = sessionId) {
        value = withContext(Dispatchers.IO) {
            resolveMdMediaFile(context, url, sessionId)
        }
    }
    return file
}

internal fun stageGuestMedia(context: Context, linuxPath: String, sessionId: String?): File? {
    if (sessionId.isNullOrBlank() && !isGlobalGuestPath(linuxPath)) {
        android.util.Log.w("MdStream", "refusing session-scoped media without session id: $linuxPath")
        return null
    }
    val fileName = linuxPath.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
    val digest = Sha256.hex("${sessionId.orEmpty()}:$linuxPath")
    val cacheFile = File(File(context.cacheDir, "markdown-media"), "$digest-$fileName")
    return runCatching {
        WorkspaceFileClient.readToFileBlocking(sessionId.orEmpty(), linuxPath, cacheFile)
        cacheFile.takeIf { it.isFile }
    }.getOrNull()
}

internal fun isGlobalGuestPath(linuxPath: String): Boolean =
    linuxPath == "/var/minis/memory" || linuxPath.startsWith("/var/minis/memory/") ||
        linuxPath == "/var/minis/skills" || linuxPath.startsWith("/var/minis/skills/") ||
        linuxPath == "/var/minis/shared" || linuxPath.startsWith("/var/minis/shared/") ||
        linuxPath == "/home/minis" || linuxPath.startsWith("/home/minis/")

internal fun filenameFromMdUrl(url: String): String {
    // Keep '#' — it's a legitimate character in attachment filenames.
    val stripped = url.substringBefore('?')
    val last = stripped.substringAfterLast('/')
    return try { java.net.URLDecoder.decode(last, "UTF-8") } catch (_: Throwable) { last }
}

internal fun openMdMediaExternally(context: Context, file: File, mime: String) {
    android.util.Log.d("MdStream", "openMdMediaExternally file=${file.absolutePath} mime=$mime")
    val authority = context.packageName + ".fileprovider"
    val uri = try {
        androidx.core.content.FileProvider.getUriForFile(context, authority, file)
    } catch (t: Throwable) {
        android.util.Log.w("MdStream", "FileProvider failed: ${t.message}")
        Uri.fromFile(file)
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    val chooser = Intent.createChooser(intent, file.name).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try { context.startActivity(chooser) } catch (t: Throwable) {
        android.util.Log.w("MdStream", "startActivity failed: ${t.message}")
    }
}

internal fun formatMdMediaMs(ms: Int): String {
    if (ms <= 0) return "0:00"
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

@Composable
internal fun RenderMdVideo(block: MdBlock.Video) {
    val context = LocalContext.current
    val colors = currentMdColors()
    val sessionId = LocalMarkdownSessionId.current
    val file = rememberMdMediaFile(block.url, sessionId)
    val filename = remember(block.url) { filenameFromMdUrl(block.url) }
    var showPlayer by remember { mutableStateOf(false) }

    val thumbnail by produceState<Bitmap?>(initialValue = null, key1 = file?.absolutePath) {
        val f = file ?: run { value = null; return@produceState }
        value = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(f.absolutePath)
                val bmp = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                android.util.Log.d("MdStream", "video thumbnail ${f.name} -> ${bmp?.width}x${bmp?.height}")
                bmp
            } catch (t: Throwable) {
                android.util.Log.w("MdStream", "video thumbnail failed: ${t.message}")
                null
            } finally {
                try { retriever.release() } catch (_: Throwable) {}
            }
        }
    }

    if (showPlayer && file != null) {
        com.openminis.app.ui.media.MinisFullscreenVideoPlayer(
            file = file,
            onDismiss = { showPlayer = false },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.inlineCodeBg)
            .border(0.5.dp, colors.tableBorder, RoundedCornerShape(8.dp))
            .clickable(enabled = file != null) {
                android.util.Log.d("MdStream", "open fullscreen video for ${file?.absolutePath}")
                showPlayer = true
            },
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 180.dp, max = 280.dp),
            contentAlignment = Alignment.Center,
        ) {
            val thumb = thumbnail
            if (thumb != null) {
                Image(
                    bitmap = thumb.asImageBitmap(),
                    contentDescription = block.alt.ifEmpty { filename },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Icon(
                imageVector = Icons.Filled.PlayCircleFilled,
                contentDescription = "Play video",
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(56.dp),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Videocam,
                contentDescription = null,
                tint = colors.blockquote,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            MdText(
                text = AnnotatedString(filename),
                fontSize = 12.sp,
                color = colors.blockquote,
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun RenderMdAudio(block: MdBlock.Audio) {
    val context = LocalContext.current
    val colors = currentMdColors()
    val sessionId = LocalMarkdownSessionId.current
    val file = rememberMdMediaFile(block.url, sessionId)
    val filename = remember(block.url) { filenameFromMdUrl(block.url) }

    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableStateOf(0) }
    var durationMs by remember { mutableStateOf(0) }

    val initialDurationMs by produceState(initialValue = 0, key1 = file?.absolutePath) {
        val f = file ?: return@produceState
        value = withContext(Dispatchers.IO) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(f.absolutePath)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toIntOrNull() ?: 0
            } catch (_: Throwable) {
                0
            } finally {
                try { retriever.release() } catch (_: Throwable) {}
            }
        }
    }

    DisposableEffect(player) {
        onDispose {
            try { player?.release() } catch (_: Throwable) {}
        }
    }

    LaunchedEffect(isPlaying, player) {
        val p = player
        while (isPlaying && p != null) {
            positionMs = try { p.currentPosition } catch (_: Throwable) { 0 }
            if (!p.isPlaying) { isPlaying = false; break }
            delay(200)
        }
    }

    val totalDurationMs = if (durationMs > 0) durationMs else initialDurationMs
    val tint = colors.link

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.inlineCodeBg)
            .border(0.5.dp, colors.tableBorder, RoundedCornerShape(10.dp))
            .clickable(enabled = file != null) {
                val f = file ?: return@clickable
                if (player == null) {
                    try {
                        val p = MediaPlayer().apply {
                            setDataSource(f.absolutePath)
                            prepare()
                            setOnCompletionListener {
                                isPlaying = false
                                positionMs = 0
                                try { seekTo(0) } catch (_: Throwable) {}
                            }
                        }
                        durationMs = p.duration
                        player = p
                        p.start()
                        isPlaying = true
                    } catch (t: Throwable) {
                        android.util.Log.w("MdStream", "audio prepare/play failed: ${t.message}")
                        openMdMediaExternally(context, f, "audio/*")
                    }
                } else {
                    val p = player ?: return@clickable
                    if (isPlaying) {
                        try { p.pause() } catch (_: Throwable) {}
                        isPlaying = false
                    } else {
                        try {
                            p.start()
                            isPlaying = true
                        } catch (_: Throwable) {}
                    }
                }
            }
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Audiotrack,
            contentDescription = null,
            tint = colors.blockquote,
            modifier = Modifier.size(18.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
        ) {
            MdText(
                text = AnnotatedString(block.alt.ifEmpty { filename }),
                fontSize = 13.sp,
                color = colors.text,
                maxLines = 1,
            )
            val progress = if (totalDurationMs > 0) positionMs.toFloat() / totalDurationMs else 0f
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
                    .height(3.dp),
                color = tint,
                trackColor = tint.copy(alpha = 0.2f),
            )
            if (totalDurationMs > 0) {
                MdText(
                    text = AnnotatedString("${formatMdMediaMs(positionMs)} / ${formatMdMediaMs(totalDurationMs)}"),
                    fontSize = 11.sp,
                    color = colors.blockquote,
                )
            }
        }
        Icon(
            imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Play",
            tint = tint,
            modifier = Modifier.size(28.dp),
        )
    }
}

/**
 * [T-android-table-hscroll-preserve] Process-level cache of table horizontal
 * ScrollStates, keyed by a STABLE table identity (message/shard/headers).
 *
 * Why not a plain `rememberScrollState()`: remember is positional. A streaming
 * publish re-parses the fragment, and when the fragment freezes the render
 * moves from the live branch to the frozen-cache branch — a different position
 * in the composition tree — so the anonymous ScrollState was recreated and the
 * user's horizontal offset snapped back to 0 on wide tables (the Android
 * sibling of iOS T-ios-table-hscroll-offset-lost, fixed by a persistent
 * per-attachment offset there). Handing out the SAME ScrollState instance for
 * the same table identity keeps the offset across recomposition, branch moves,
 * and re-parses.
 *
 * Key uses the header row (stable from the moment a table starts streaming —
 * rows append below it) rather than full content, which would change on every
 * appended row. Two tables with an identical header row in the SAME shard
 * would share an offset — acceptable: they'd have identical column layouts.
 * LRU-bounded so long sessions can't accumulate states without limit.
 */
internal object TableHScrollStates {
    private const val MAX_ENTRIES = 64

    // Access-ordered LinkedHashMap LRU (pure Kotlin — android.util.LruCache is
    // a throwing stub in JVM unit tests, and this object is unit-tested).
    private val states = object : LinkedHashMap<String, ScrollState>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ScrollState>): Boolean =
            size > MAX_ENTRIES
    }

    fun stateFor(key: String): ScrollState =
        synchronized(states) {
            states.getOrPut(key) { ScrollState(0) }
        }
}

@Composable
internal fun RenderTable(block: MdBlock.Table) {
    val colors = currentMdColors()
    val colCount = maxOf(block.headers.size, block.rows.maxOfOrNull { it.size } ?: 0)
    if (colCount == 0) return

    // Build the list of rows — header first if present
    val allRows: List<List<String>> = buildList {
        if (block.headers.isNotEmpty()) add(block.headers)
        addAll(block.rows)
    }

    // [T-android-markdown-table-copy-actions] Copy Table (markdown text) +
    // Copy Table Image (rendered bitmap), aligning with iOS
    // SelectableMarkdownView.copyTable / copyTableImage. These are NOT a
    // separate popup: the table publishes them to the SelectionController so
    // the ONE selection toolbar appends them after Copy / Copy Markdown / etc.
    // A long-press on a table cell already starts a text selection (cells are
    // MdText shards), which is what surfaces that toolbar.
    val context = LocalContext.current
    val tableScope = rememberCoroutineScope()
    val tableGraphicsLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val tableCopiedToast = stringResource(R.string.markdown_table_copied_toast)
    val tableImageCopiedToast = stringResource(R.string.markdown_table_image_copied_toast)
    val tableImageCopyFailedToast = stringResource(R.string.markdown_table_image_copy_failed_toast)

    val selectionController = LocalMinisSelectionController.current
    val shardIdForTable = LocalShardId.current
    val messageId = shardIdForTable?.messageId

    // [T-android-table-hscroll-preserve] Stable identity-keyed ScrollState so
    // streaming re-parses / the live→frozen branch move don't reset the user's
    // horizontal offset (see TableHScrollStates). Falls back to an anonymous
    // state when no shard id is available (non-chat contexts).
    val tableHScroll = if (shardIdForTable != null) {
        val hScrollKey = "${shardIdForTable.messageId}/${shardIdForTable.shardId}" +
            "/tbl:${block.headers.joinToString("|")}"
        remember(hScrollKey) { TableHScrollStates.stateFor(hScrollKey) }
    } else {
        rememberScrollState()
    }
    if (selectionController != null && messageId != null) {
        // Re-register whenever the inputs that the actions close over change.
        androidx.compose.runtime.DisposableEffect(selectionController, messageId, block.raw) {
            val actions = SelectionController.TableActions(
                copyTableMarkdown = {
                    val md = block.raw
                    if (md.isNotEmpty()) {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("table", md))
                        com.openminis.app.ui.components.MinisToast.show(context, tableCopiedToast)
                    }
                },
                copyTableImage = {
                    tableScope.launch {
                        try {
                            val imageBitmap = tableGraphicsLayer.toImageBitmap()
                            val androidBitmap = imageBitmap.asAndroidBitmap()
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                val shareDir = java.io.File(context.cacheDir, "share").apply { mkdirs() }
                                val outFile = java.io.File(shareDir, "table_${System.currentTimeMillis()}.png")
                                outFile.outputStream().use {
                                    androidBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                                }
                                val uri = androidx.core.content.FileProvider.getUriForFile(
                                    context,
                                    "${context.packageName}.fileprovider",
                                    outFile,
                                )
                                context.grantUriPermission(
                                    "*", uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                                )
                                val clip = android.content.ClipData.newUri(
                                    context.contentResolver, "table-image", uri,
                                )
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                        as android.content.ClipboardManager
                                    cm.setPrimaryClip(clip)
                                    com.openminis.app.ui.components.MinisToast.show(context, tableImageCopiedToast)
                                }
                            }
                        } catch (e: Exception) {
                            com.openminis.app.ui.components.MinisToast.show(context, tableImageCopyFailedToast)
                        }
                    }
                    Unit
                },
            )
            selectionController.rememberTableActions(messageId, actions)
            onDispose { selectionController.forgetTableActions(messageId) }
        }
    }

    // BoxWithConstraints (OUTSIDE horizontalScroll) reads the real viewport width —
    // inside a horizontalScroll container, maxWidth would be Constraints.Infinity.
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
    ) {
        val viewportWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) { maxWidth.toPx() }.toInt()
        // Inner box owns the rounded border/clip and horizontal scroll. Border + clip
        // are applied before horizontalScroll so the frame stays anchored to the visible
        // viewport when the table is wider than the screen.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(if (ChatColors.isDark) Color.Transparent else Color.White)
                .border(1.dp, colors.tableBorder, RoundedCornerShape(14.dp))
                // Record this draw pass into the GraphicsLayer so Copy Table
                // Image can materialise the styled table (cells, borders, header
                // shading, inline code) via toImageBitmap() — drawLayer also
                // renders the recorded content as the visible output. Wraps the
                // bordered frame so the captured bitmap includes the border. For
                // a table wider than the viewport this captures the visible
                // (scrolled) frame, matching what the user sees on screen.
                .drawWithContent {
                    tableGraphicsLayer.record { this@drawWithContent.drawContent() }
                    drawLayer(tableGraphicsLayer)
                }
                .horizontalScroll(tableHScroll),
        ) {
        val lineColor = colors.tableBorder
        val lineStrokePx = with(androidx.compose.ui.platform.LocalDensity.current) { 1.dp.toPx() }
        val totalRowCount = allRows.size
        androidx.compose.ui.layout.Layout(
            content = {
                for ((rowIndex, cells) in allRows.withIndex()) {
                    val isHeader = rowIndex == 0 && block.headers.isNotEmpty()
                    val isLastRow = rowIndex == totalRowCount - 1
                    for (colIndex in 0 until colCount) {
                        val isLastCol = colIndex == colCount - 1
                        Box(
                            modifier = Modifier
                                .then(if (isHeader) Modifier.background(colors.tableHeaderBg) else Modifier)
                                .drawBehind {
                                    // Rows are separated by hairlines only; columns are spaced, not ruled (the board's table).
                                    // Bottom divider between rows
                                    if (!isLastRow) {
                                        drawLine(
                                            color = lineColor,
                                            start = androidx.compose.ui.geometry.Offset(0f, size.height - lineStrokePx / 2),
                                            end = androidx.compose.ui.geometry.Offset(size.width, size.height - lineStrokePx / 2),
                                            strokeWidth = lineStrokePx,
                                        )
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                            // The column's alignment from the delimiter row (`:--`, `:-:`, `--:`).
                            contentAlignment = when (block.aligns.getOrNull(colIndex)) {
                                com.openminis.app.ui.chat.md.Align.CENTER -> Alignment.Center
                                com.openminis.app.ui.chat.md.Align.RIGHT -> Alignment.CenterEnd
                                else -> Alignment.CenterStart
                            },
                        ) {
                            val cellText = cells.getOrElse(colIndex) { "" }
                            MdText(
                                text = MarkdownParseCaches.inline(cellText, colors),
                                fontSize = 14.sp,
                                fontWeight = if (isHeader) FontWeight.SemiBold else null,
                                color = colors.text,
                                inlineContent = rememberKatexInlineContent(14.sp, MarkdownParseCaches.mathLatex(cellText)),
                                // Long-press selects the whole cell rather than a
                                // sentence-fragment of it: a cell holding "1,200"
                                // or "v1.2, beta" would otherwise stop at the
                                // comma, which is never what someone pressing a
                                // table cell is after.
                                isAtomicSelectionUnit = true,
                            )
                        }
                    }
                }
            },
        ) { measurables, _ ->
            val rowCount = allRows.size

            // Safe upper bound for intrinsic queries and constraint widths. Compose's
            // Constraints packs width into 18 bits, so values above ~262k will throw.
            // [T-android-table-col-cap-ios-parity] Cap each column at 5× the
            // viewport width, matching iOS [TableColumnCap] (SelectableMarkdownView
            // computeLayout: min(width * 5, requested)). The previous 1× cap forced
            // any long-text column to wrap at exactly one screen width, producing
            // tall many-line cells; iOS keeps such rows on one line and lets
            // horizontal scroll handle the overflow (tighter caps were reverted
            // there after user feedback 2026-05-13). 5× viewport (~5-7k px) stays
            // far below the 262k Constraints limit while still bounding
            // pathological intrinsics from unbroken strings.
            val maxCellWidth = (viewportWidthPx * 5).coerceAtLeast(1)

            // Pass 1: use intrinsic widths (no measure() call) to compute per-column max width.
            // Compose forbids calling measure() twice on the same Measurable in one layout pass.
            // Pass height=0 (standard "no height constraint" sentinel for intrinsic queries).
            val colWidths = IntArray(colCount)
            for (rowIdx in 0 until rowCount) {
                for (colIdx in 0 until colCount) {
                    val idx = rowIdx * colCount + colIdx
                    if (idx < measurables.size) {
                        val intrinsic = measurables[idx].maxIntrinsicWidth(0)
                            .coerceIn(0, maxCellWidth)
                        colWidths[colIdx] = maxOf(colWidths[colIdx], intrinsic)
                    }
                }
            }

            // Expand-to-fill: if natural content width is narrower than the viewport,
            // grow the last column to fill the remaining space (matches iOS behavior).
            val naturalWidth = colWidths.sum()
            if (naturalWidth < viewportWidthPx && colCount > 0) {
                colWidths[colCount - 1] += viewportWidthPx - naturalWidth
            }

            // Pass 2: measure each cell exactly once, with its column's fixed width.
            val rowHeights = IntArray(rowCount)
            val placeables = Array(rowCount) { rowIdx ->
                Array(colCount) { colIdx ->
                    val idx = rowIdx * colCount + colIdx
                    val m = measurables[idx]
                    val colW = colWidths[colIdx].coerceIn(0, maxCellWidth)
                    val p = m.measure(
                        androidx.compose.ui.unit.Constraints.fixedWidth(colW)
                    )
                    rowHeights[rowIdx] = maxOf(rowHeights[rowIdx], p.height)
                    p
                }
            }

            // Cells are laid out edge-to-edge; dividers are painted inside each cell
            // via drawBehind, so no extra spacing between cells is needed here.
            val totalWidth = colWidths.sum()
            val totalHeight = rowHeights.sum()

            layout(totalWidth, totalHeight) {
                var y = 0
                for (rowIdx in 0 until rowCount) {
                    var x = 0
                    for (colIdx in 0 until colCount) {
                        val p = placeables[rowIdx][colIdx]
                        p.place(x, y + (rowHeights[rowIdx] - p.height) / 2)
                        x += colWidths[colIdx]
                    }
                    y += rowHeights[rowIdx]
                }
            }
        }
        }
    }
}

// ─── [T-android-inline-parse-offmain] Parse caches ──────────────────────────
//
// RenderBlock used to call parseInline / collectInlineMathLatex DIRECTLY in
// composition — on the main thread, un-remembered, so every recomposition of a
// visible block re-ran the inline scan, and the LIVE block re-ran it on every
// streaming publish. That is the main-thread regex/ICU load in the
// minis-2026-06-10 ANR stack. All call sites now go through these process-wide
// LRUs; the streaming parse paths PREWARM them on Dispatchers.Default before
// publishing blocks, so the subsequent main-thread composition is a pure cache
// hit. Inputs are pure functions of (text [, colors]) and the outputs
// (AnnotatedString / List<String> / List<MdBlock>) are immutable, so
// cross-thread sharing is safe. MdColors is a data class → structural key;
// theme switches simply mint new entries and old ones age out.
/**
 * [T-android-stream-render-profile] Always-on, low-overhead aggregate profiler
 * for the live streaming markdown render. Accumulates the per-tick off-main
 * parse time (block split + prewarm/incremental inline+math) of the live tail
 * and emits ONE summary line every [FLUSH_TICKS] ticks (not per tick — keeps
 * log volume + the logging cost itself negligible). Gives a directly-comparable
 * "how heavy is streaming render per tick" number for benchmarking (e.g.
 * incremental on vs off) and a standing signal in real-device use. Verified on
 * a Pixel 4a with DeepSeek V4 Flash at a 30432-char single reply: parse avg
 * 3-8ms / max ~15ms even as the live fragment grew, native heap flat 55-88MB
 * (vs the pre-fix 42↔207MB GC storm), 0 hangs.
 */
internal object StreamRenderProfiler {
    private const val FLUSH_TICKS = 20
    private var ticks = 0
    private var parseMsSum = 0.0
    private var parseMsMax = 0.0
    private var lastFragLen = 0
    private var maxFragLen = 0

    /** One off-main live-tick: block split + prewarm/incremental inline+math. */
    @Synchronized
    fun recordParse(fragLen: Int, ms: Double) {
        parseMsSum += ms
        if (ms > parseMsMax) parseMsMax = ms
        lastFragLen = fragLen
        if (fragLen > maxFragLen) maxFragLen = fragLen
        ticks++
        if (ticks < FLUSH_TICKS) return
        val n = ticks
        com.openminis.app.logging.AppLogger.info(
            "StreamRender",
            "[StreamRender] ticks=$n fragLen=$lastFragLen(max=$maxFragLen) " +
                "parseMs avg=${"%.1f".format(parseMsSum / n)} max=${"%.1f".format(parseMsMax)}",
        )
        ticks = 0; parseMsSum = 0.0; parseMsMax = 0.0; maxFragLen = 0
    }
}
