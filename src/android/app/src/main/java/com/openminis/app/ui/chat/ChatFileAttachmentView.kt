package com.openminis.app.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.R
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.sandbox.CsvContentPreview
import com.openminis.app.ui.sandbox.FileCategory
import com.openminis.app.ui.sandbox.FileItem
import com.openminis.app.ui.sandbox.JsonContentPreview
import com.openminis.app.ui.sandbox.TextContentPreview
import com.openminis.app.ui.sandbox.parseCsvLine
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Chat attachment view supporting both Compact File Card and Inline Preview modes.
 *
 * Rules:
 * - PDF / HTML: default compact card (no heavy PdfRenderer or WebView in LazyColumn).
 * - Text / Code / JSON / Markdown: short content default inline, long content default compact card.
 * - Unknown files: compact file card.
 * - Each file's expand/collapse state is independently tracked and does not affect other messages.
 */
@Composable
fun ChatFileAttachmentView(
    fileItem: FileItem,
    modifier: Modifier = Modifier,
    onOpenFullPreview: (FileItem) -> Unit = {},
) {
    val category = fileItem.category
    val fileKey = remember(fileItem) {
        fileItem.file.absolutePath.ifEmpty { fileItem.name }
    }

    // Determine whether the content is considered "short" for text-based files
    var isShortContent by remember(fileKey) { mutableStateOf<Boolean?>(null) }
    var fileContent by remember(fileKey) { mutableStateOf<String?>(null) }
    var csvRows by remember(fileKey) { mutableStateOf<List<List<String>>?>(null) }

    val isTextBased = category == FileCategory.TEXT ||
        category == FileCategory.CODE ||
        category == FileCategory.JSON ||
        category == FileCategory.MARKDOWN ||
        category == FileCategory.CSV

    LaunchedEffect(fileKey) {
        if (isTextBased && fileItem.file.exists() && fileItem.file.isFile) {
            withContext(Dispatchers.IO) {
                try {
                    val length = fileItem.file.length()
                    if (length > 4096L) {
                        isShortContent = false
                    } else {
                        val text = fileItem.file.readText(Charsets.UTF_8)
                        val lineCount = text.lines().size
                        val short = lineCount <= 20 && length <= 2048L
                        isShortContent = short
                        fileContent = text
                        if (category == FileCategory.CSV) {
                            val sep = if (fileItem.file.extension.equals("tsv", true)) '\t' else ','
                            csvRows = text.lines().filter { it.isNotBlank() }.take(50).map { parseCsvLine(it, sep) }
                        }
                    }
                } catch (_: Exception) {
                    isShortContent = false
                }
            }
        } else {
            isShortContent = false
        }
    }

    // Default mode: short text-based files show inline preview; everything else starts as compact card
    val defaultInline = isTextBased && (isShortContent == true)
    var isInlineMode by rememberSaveable(fileKey, isShortContent) {
        mutableStateOf(defaultInline)
    }

    // Independent expansion state inside inline mode (bounded vs fully expanded)
    var isInlineHeightExpanded by rememberSaveable(fileKey) {
        mutableStateOf(false)
    }

    val cardShape = RoundedCornerShape(10.dp)
    val cardBorder = 0.5.dp
    val borderColor = ChatColors.thumbnailBorder
    val bgColor = ChatColors.secondaryBg

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .animateContentSize(),
    ) {
        if (!isInlineMode || !isTextBased) {
            // ─── Compact File Card ──────────────────────────────────────
            CompactFileCard(
                fileItem = fileItem,
                canInlinePreview = isTextBased,
                onToggleInline = { isInlineMode = true },
                onOpenFull = { onOpenFullPreview(fileItem) },
            )
        } else {
            // ─── Inline Preview ─────────────────────────────────────────
            InlineFilePreview(
                fileItem = fileItem,
                content = fileContent,
                csvRows = csvRows,
                isHeightExpanded = isInlineHeightExpanded,
                onToggleHeight = { isInlineHeightExpanded = !isInlineHeightExpanded },
                onCollapseToCard = { isInlineMode = false },
                onOpenFull = { onOpenFullPreview(fileItem) },
            )
        }
    }
}

/**
 * Compact File Card showing file icon, file name, type, formatted size,
 * and entry points for preview or external open.
 */
@Composable
private fun CompactFileCard(
    fileItem: FileItem,
    canInlinePreview: Boolean,
    onToggleInline: () -> Unit,
    onOpenFull: () -> Unit,
) {
    val cardShape = RoundedCornerShape(10.dp)

    Surface(
        shape = cardShape,
        color = ChatColors.secondaryBg,
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .border(0.5.dp, ChatColors.thumbnailBorder, cardShape)
            .clickable(onClick = onOpenFull),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // File type icon box
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = fileItem.category.icon,
                    contentDescription = fileItem.category.displayName,
                    tint = ChatColors.link,
                    modifier = Modifier.size(24.dp),
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // File name, type & size
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = fileItem.name,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        fontSize = 14.sp,
                    ),
                    color = ChatColors.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(modifier = Modifier.height(2.dp))

                val sizeText = if (fileItem.size > 0L) fileItem.formattedSize else null
                val subtitle = if (sizeText != null) {
                    "${fileItem.category.displayName} • $sizeText"
                } else {
                    fileItem.category.displayName
                }

                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = ChatColors.secondaryText,
                    maxLines = 1,
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Action entry points
            if (canInlinePreview) {
                MinisTextButton(
                    onClick = onToggleInline,
                    modifier = Modifier.height(32.dp),
                ) {
                    Text(
                        text = stringResource(R.string.chat_attachment_preview_action),
                        fontSize = 12.sp,
                        color = ChatColors.link,
                    )
                }
            } else {
                MinisTextButton(
                    onClick = onOpenFull,
                    modifier = Modifier.height(32.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.chat_attachment_open_action),
                            fontSize = 12.sp,
                            color = ChatColors.link,
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = ChatColors.link,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Inline File Preview with capped height, expand/collapse toggle, and Full View option.
 */
@Composable
private fun InlineFilePreview(
    fileItem: FileItem,
    content: String?,
    csvRows: List<List<String>>?,
    isHeightExpanded: Boolean,
    onToggleHeight: () -> Unit,
    onCollapseToCard: () -> Unit,
    onOpenFull: () -> Unit,
) {
    val cardShape = RoundedCornerShape(10.dp)
    val maxPreviewHeight = if (isHeightExpanded) null else 220.dp

    Surface(
        shape = cardShape,
        color = ChatColors.secondaryBg,
        tonalElevation = 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .border(0.5.dp, ChatColors.thumbnailBorder, cardShape),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header: Icon + Name + Full View Button + Collapse to Card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = fileItem.category.icon,
                    contentDescription = null,
                    tint = ChatColors.link,
                    modifier = Modifier.size(16.dp),
                )

                Spacer(modifier = Modifier.width(6.dp))

                Text(
                    text = fileItem.name,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Medium,
                    ),
                    color = ChatColors.primaryText,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )

                MinisTextButton(
                    onClick = onOpenFull,
                    modifier = Modifier.height(28.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = stringResource(R.string.chat_attachment_full_view),
                            fontSize = 11.sp,
                            color = ChatColors.link,
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(12.dp),
                            tint = ChatColors.link,
                        )
                    }
                }
            }

            HorizontalDivider(color = ChatColors.thumbnailBorder, thickness = 0.5.dp)

            // Body: Render the content
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
            ) {
                when {
                    content == null && csvRows == null -> {
                        Text(
                            text = stringResource(R.string.filepreview_loading),
                            style = MaterialTheme.typography.bodySmall,
                            color = ChatColors.secondaryText,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                    fileItem.category == FileCategory.JSON && content != null -> {
                        JsonContentPreview(
                            jsonString = content,
                            maxHeight = maxPreviewHeight,
                            showCopyButton = true,
                        )
                    }
                    fileItem.category == FileCategory.CSV && csvRows != null -> {
                        CsvContentPreview(
                            rows = csvRows,
                            maxHeight = maxPreviewHeight,
                        )
                    }
                    fileItem.category == FileCategory.MARKDOWN && content != null -> {
                        MarkdownDocument(
                            content = content,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = maxPreviewHeight ?: 600.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                        )
                    }
                    content != null -> {
                        TextContentPreview(
                            content = content,
                            isMonospace = fileItem.category == FileCategory.CODE,
                            maxHeight = maxPreviewHeight,
                            showCopyButton = true,
                        )
                    }
                }
            }

            HorizontalDivider(color = ChatColors.thumbnailBorder, thickness = 0.5.dp)

            // Footer: Expand/Collapse height toggle + Collapse back to compact card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                MinisTextButton(
                    onClick = onToggleHeight,
                    modifier = Modifier.height(28.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (isHeightExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = ChatColors.secondaryText,
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = stringResource(
                                if (isHeightExpanded) R.string.chat_attachment_collapse_preview
                                else R.string.chat_attachment_expand_preview
                            ),
                            fontSize = 11.sp,
                            color = ChatColors.secondaryText,
                        )
                    }
                }

                MinisTextButton(
                    onClick = onCollapseToCard,
                    modifier = Modifier.height(28.dp),
                ) {
                    Text(
                        text = stringResource(R.string.chat_attachment_card_mode),
                        fontSize = 11.sp,
                        color = ChatColors.secondaryText,
                    )
                }
            }
        }
    }
}

/**
 * Chat attachment entry point accepting a markdown link title and URL.
 * Automatically resolves the host file, determines category, and routes full preview.
 */
@Composable
fun ChatFileAttachmentView(
    title: String,
    url: String,
    modifier: Modifier = Modifier,
) {
    val sessionId = LocalMarkdownSessionId.current
    val urlHandler = LocalMarkdownUrlClickHandler.current
    val hostFile = rememberMdMediaFile(url, sessionId)
    val fallbackName = remember(title, url) {
        if (title.isNotBlank()) title
        else {
            val last = url.substringAfterLast('/').substringBefore('?')
            runCatching { java.net.URLDecoder.decode(last, "UTF-8") }.getOrDefault(last).ifEmpty { "file" }
        }
    }
    val fileItem = remember(hostFile, fallbackName, url) {
        if (hostFile != null) {
            FileItem(
                file = hostFile,
                name = fallbackName,
                isDirectory = false,
                isSymlink = false,
                size = if (hostFile.exists()) hostFile.length() else 0L,
                modifiedMs = if (hostFile.exists()) hostFile.lastModified() else 0L,
            )
        } else {
            FileItem(
                file = File(url),
                name = fallbackName,
                isDirectory = false,
                isSymlink = false,
                size = 0L,
                modifiedMs = 0L,
            )
        }
    }
    ChatFileAttachmentView(
        fileItem = fileItem,
        modifier = modifier,
        onOpenFullPreview = {
            urlHandler?.invoke(url)
        },
    )
}

