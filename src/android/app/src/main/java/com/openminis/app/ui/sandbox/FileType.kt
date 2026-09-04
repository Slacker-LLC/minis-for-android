package com.openminis.app.ui.sandbox

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.DataUsage
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.VideoFile
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Unified file category categorization across chat, sandbox, and file preview.
 * Eliminates duplicated extension checks across the codebase.
 */
enum class FileCategory(
    val displayName: String,
    val isInlineByDefault: Boolean,
) {
    IMAGE("Image", isInlineByDefault = true),
    GIF("GIF", isInlineByDefault = true),
    AUDIO("Audio", isInlineByDefault = true),
    VIDEO("Video", isInlineByDefault = true),
    MARKDOWN("Markdown", isInlineByDefault = false), // dynamically resolved: short = inline, long = card
    CODE("Code", isInlineByDefault = false),
    JSON("JSON", isInlineByDefault = false),
    TEXT("Text", isInlineByDefault = false),
    PDF("PDF", isInlineByDefault = false),
    HTML("HTML", isInlineByDefault = false),
    CSV("CSV", isInlineByDefault = false),
    ARCHIVE("Archive", isInlineByDefault = false),
    OFFICE("Document", isInlineByDefault = false),
    UNKNOWN("File", isInlineByDefault = false);

    val icon: ImageVector
        get() = when (this) {
            IMAGE, GIF -> Icons.Default.Image
            AUDIO -> Icons.Default.AudioFile
            VIDEO -> Icons.Default.VideoFile
            PDF -> Icons.Default.PictureAsPdf
            MARKDOWN -> Icons.AutoMirrored.Filled.Article
            HTML -> Icons.Default.Language
            CODE -> Icons.Default.Terminal
            JSON -> Icons.Default.DataUsage
            CSV, TEXT -> Icons.AutoMirrored.Filled.Article
            ARCHIVE -> Icons.Default.FolderZip
            OFFICE -> Icons.AutoMirrored.Filled.Article
            UNKNOWN -> Icons.AutoMirrored.Filled.InsertDriveFile
        }
}

/**
 * Single source of truth for classifying a filename into a [FileCategory].
 */
fun fileCategoryFor(fileName: String): FileCategory {
    val cleanName = fileName.substringBefore('?').substringBefore('#')
    val ext = cleanName.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "gif" -> FileCategory.GIF
        "png", "jpg", "jpeg", "bmp", "webp", "ico", "svg" -> FileCategory.IMAGE
        "mp3", "wav", "aac", "flac", "ogg", "m4a" -> FileCategory.AUDIO
        "mp4", "mov", "avi", "mkv", "webm", "m4v" -> FileCategory.VIDEO
        "pdf" -> FileCategory.PDF
        "md", "markdown", "mdown", "mkd" -> FileCategory.MARKDOWN
        "html", "htm", "xhtml" -> FileCategory.HTML
        "json" -> FileCategory.JSON
        "csv", "tsv" -> FileCategory.CSV
        "sh", "bash", "zsh", "fish",
        "py", "js", "ts", "kt", "java", "c", "cpp", "h", "m", "swift",
        "rs", "go", "rb", "php", "lua", "pl", "css", "scss", "toml",
        "env", "gitignore", "dockerfile", "makefile", "xml", "yaml", "yml", "sql" -> FileCategory.CODE
        "txt", "log", "conf", "cfg", "ini" -> FileCategory.TEXT
        "zip", "tar", "gz", "bz2", "xz", "7z", "rar", "jar", "apk", "aar", "deb", "rpm" -> FileCategory.ARCHIVE
        "xlsx", "xls", "docx", "doc", "pptx", "ppt", "odt", "ods", "odp" -> FileCategory.OFFICE
        "" -> FileCategory.TEXT // extensionless files treated as text
        else -> FileCategory.UNKNOWN
    }
}
