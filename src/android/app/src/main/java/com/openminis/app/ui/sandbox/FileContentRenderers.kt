package com.openminis.app.ui.sandbox

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Shared, page-independent content renderer for text and code files.
 * Used by both [FilePreviewScreen] and chat inline preview.
 */
@Composable
fun TextContentPreview(
    content: String,
    modifier: Modifier = Modifier,
    isMonospace: Boolean = true,
    maxHeight: Dp? = null,
    showCopyButton: Boolean = false,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    if (copied) {
        LaunchedEffect(Unit) {
            delay(1500)
            copied = false
        }
    }

    Column(modifier = modifier) {
        if (showCopyButton) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Spacer(modifier = Modifier.weight(1f))
                IconButton(
                    onClick = {
                        clipboard.setText(AnnotatedString(content))
                        copied = true
                    },
                    modifier = Modifier.size(28.dp),
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = if (copied) "Copied" else "Copy",
                        tint = if (copied) Color(0xFF34C759) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        val scrollMod = if (maxHeight != null) {
            Modifier
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .verticalScroll(rememberScrollState())
        } else {
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        }

        Box(modifier = scrollMod) {
            Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = if (isMonospace) FontFamily.Monospace else FontFamily.Default,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                    ),
                    softWrap = false,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
    }
}

/**
 * Shared, page-independent content renderer for JSON files.
 * Pretty prints JSON object / array and provides code styling and copy.
 */
@Composable
fun JsonContentPreview(
    jsonString: String,
    modifier: Modifier = Modifier,
    maxHeight: Dp? = null,
    showCopyButton: Boolean = true,
) {
    val formatted = remember(jsonString) {
        try {
            when (jsonString.trimStart().firstOrNull()) {
                '{' -> org.json.JSONObject(jsonString).toString(2)
                '[' -> org.json.JSONArray(jsonString).toString(2)
                else -> jsonString
            }
        } catch (_: Exception) {
            jsonString
        }
    }

    TextContentPreview(
        content = formatted,
        modifier = modifier,
        isMonospace = true,
        maxHeight = maxHeight,
        showCopyButton = showCopyButton,
    )
}

/**
 * Shared, page-independent content renderer for CSV/TSV files.
 */
@Composable
fun CsvContentPreview(
    rows: List<List<String>>,
    modifier: Modifier = Modifier,
    maxHeight: Dp? = null,
) {
    val baseMod = if (maxHeight != null) {
        modifier.heightIn(max = maxHeight)
    } else {
        modifier
    }

    LazyColumn(
        modifier = baseMod
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        items(rows.size) { rowIdx ->
            val cols = rows[rowIdx]
            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                cols.forEach { cell ->
                    Text(
                        text = cell,
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            fontWeight = if (rowIdx == 0) FontWeight.Bold else FontWeight.Normal,
                        ),
                        modifier = Modifier
                            .padding(end = 16.dp)
                            .width(140.dp),
                        maxLines = 1,
                    )
                }
            }
            if (rowIdx == 0) HorizontalDivider()
        }
    }
}

/**
 * Robust CSV/TSV line parser handling quoted cells and custom delimiters.
 */
fun parseCsvLine(line: String, sep: Char): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var inQuotes = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                cur.append('"'); i += 2; continue
            }
            c == '"' -> inQuotes = !inQuotes
            c == sep && !inQuotes -> { out.add(cur.toString()); cur.clear() }
            else -> cur.append(c)
        }
        i++
    }
    out.add(cur.toString())
    return out
}
