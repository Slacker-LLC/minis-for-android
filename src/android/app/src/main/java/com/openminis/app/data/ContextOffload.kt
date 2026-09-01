package com.openminis.app.data

import android.content.Context
import com.openminis.app.logging.AppLogger
import com.openminis.app.runtime.minisd.WorkspaceFileClient
import com.openminis.app.tools.internal.TextRetainer
import java.util.UUID

/**
 * Per-session offload storage helpers — write large tool outputs to disk so
 * the model can `file_read` them later while we replace the in-history copy
 * with a tiny `[CONTEXT OFFLOADED] ... <linux-path>` stub.
 *
 * Mirrors iOS `AIChatViewModel.minisOffloadsPersistentDir(for:)`,
 * `offloadContextContent(_:toolId:toolName:ext:)`, and
 * `offloadContextImage(_:toolId:mimeType:)` (AIChatViewModel.swift:6964 +
 * 7170 + 7188). Same path layout — `.../offloads/tools/<name>_<id>.<ext>` —
 * so file_read paths round-trip across platforms when an Android-offloaded
 * session is opened on iOS (or vice versa) via cloud sync.
 *
 * Linux-visible mount: `/var/minis/offloads/tools/<file>`. The bytes are
 * written through minisd because the App UID cannot open the canonical host
 * backing directory under SELinux enforcing.
 */
object ContextOffload {
    /** Linux-side mount point — keep in lock-step with iOS `minisOffloadsLinuxDir`. */
    const val LINUX_OFFLOADS_DIR = "/var/minis/offloads"

    /** Sentinel prefix on stub strings — the agent loop checks this to skip
     *  re-offloading parts that have already been processed. Mirrors iOS. */
    const val OFFLOADED_PREFIX = "[CONTEXT OFFLOADED]"

    /**
     * Take the last 12 chars of [toolId] as a short, locally-unique suffix
     * for the on-disk filename. Anthropic IDs are `toolu_01…` (constant
     * 8-char prefix), so the trailing 12 chars are still distinguishing.
     * Mirrors iOS `shortToolId(_:)`.
     */
    private fun shortToolId(toolId: String): String =
        if (toolId.length <= 12) toolId else toolId.takeLast(12)

    private fun sanitize(name: String): String =
        name.ifEmpty { "tool" }.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /**
     * Write tool text content to disk and return the Linux-visible path
     * the model can later pass to `file_read`. Returns the empty string
     * on any I/O failure — caller should still update the in-history part
     * with a stub so the model isn't left holding the original bytes.
     */
    suspend fun offloadContent(
        context: Context,
        sessionId: String,
        content: String,
        toolId: String,
        toolName: String,
        ext: String = "txt",
    ): String {
        val fileName = "${sanitize(toolName)}_${shortToolId(toolId)}.$ext"
        val linuxPath = "$LINUX_OFFLOADS_DIR/tools/$fileName"
        return try {
            WorkspaceFileClient.writeBytes(sessionId, linuxPath, content.toByteArray(Charsets.UTF_8))
            linuxPath
        } catch (e: Exception) {
            AppLogger.warning(TAG, "offloadContent failed: ${e.message}")
            ""
        }
    }

    /**
     * Write tool image bytes to disk and return the Linux-visible path.
     * Extension derived from MIME type — falls through to `.bin` for
     * unrecognised types so the file_read path still resolves something
     * the model can preview.
     */
    suspend fun offloadImage(
        context: Context,
        sessionId: String,
        bytes: ByteArray,
        toolId: String,
        mimeType: String,
    ): String {
        val ext = when (mimeType) {
            "image/png" -> "png"
            "image/jpeg" -> "jpg"
            "image/gif" -> "gif"
            "image/webp" -> "webp"
            else -> "bin"
        }
        val fileName = "image_${shortToolId(toolId)}.$ext"
        val linuxPath = "$LINUX_OFFLOADS_DIR/tools/$fileName"
        return try {
            WorkspaceFileClient.writeBytes(sessionId, linuxPath, bytes)
            linuxPath
        } catch (e: Exception) {
            AppLogger.warning(TAG, "offloadImage failed: ${e.message}")
            ""
        }
    }

    data class SpillResult(
        val inline: String,
        val linuxPath: String?,
        val spilled: Boolean,
    )

    suspend fun spillIfOversized(
        sessionId: String,
        text: String,
        maxInlineBytes: Int = 50 * 1024,
        baseName: String,
    ): SpillResult {
        val totalBytes = text.toByteArray(Charsets.UTF_8).size
        if (totalBytes <= maxInlineBytes) {
            return SpillResult(text, null, false)
        }
        val fileName = "${sanitize(baseName)}-${System.currentTimeMillis()}-${UUID.randomUUID()}.txt"
        val linuxPath = "$LINUX_OFFLOADS_DIR/tools/$fileName"
        WorkspaceFileClient.writeBytes(sessionId, linuxPath, text.toByteArray(Charsets.UTF_8))
        val preview = TextRetainer(
            maxChars = 3_000,
            headChars = 2_000,
            tailChars = 1_000,
        ).also { it.push(text) }.finish().text
        return SpillResult(
            inline = "$preview\n\n(Omitted $totalBytes bytes. Full formatted result stored at: $linuxPath. Use file_read with offset/limit to search within it.)",
            linuxPath = linuxPath,
            spilled = true,
        )
    }

    /**
     * Build the in-history stub that replaces an offloaded part. Format
     * is identical to iOS so a session opened on either platform shows
     * the same `[CONTEXT OFFLOADED] …` text where the real bytes used
     * to be.
     */
    fun stub(approxTokens: Int, byteCount: Int, linuxPath: String): String =
        "$OFFLOADED_PREFIX Content (~$approxTokens tokens, $byteCount bytes) saved to: $linuxPath\n" +
            "Use file_read tool to retrieve if needed."

    private const val TAG = "ContextOffload"
}
