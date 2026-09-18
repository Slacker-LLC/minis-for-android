package com.openminis.app.tools

import java.net.URLDecoder

/**
 * [T-eta-file-vision] Where a `read_image` argument may point.
 *
 * Ported from Eta `agent/model/AgentFileVisionToolCatalog.kt` (Mangi-11/Eta @
 * c15de97), which accepts "any absolute image path, file URI or system gallery
 * content URI" and keeps the decision in one place shared by the tool schema and the
 * executor.
 *
 * Adapted to this app: the gallery URIs are the ones `android.media.images` hands
 * out, so a MediaStore URI is read directly instead of forcing the old
 * list -> `android-photos export` -> read detour. Any other `content://` authority
 * stays refused rather than turning the tool into a generic provider reader, and a
 * remote URL is refused because nothing here downloads.
 */
sealed class ImageSource {
    /** A path inside the guest/workspace filesystem, already resolved from its scheme. */
    data class LinuxPath(val path: String) : ImageSource()

    /** A MediaStore image URI, read through this app's own content resolver. */
    data class MediaContentUri(val uri: String) : ImageSource()

    data class Refused(val reason: String) : ImageSource()
}

object ImageSourcePolicy {

    /** Authority prefix of the URIs `android.media.images` returns. */
    const val MEDIA_URI_PREFIX = "content://media/"

    fun classify(raw: String): ImageSource {
        val value = raw.trim()
        if (value.isEmpty()) return ImageSource.Refused("'path' is required")
        return when {
            value.startsWith("minis://") -> ImageSource.LinuxPath("/var/minis/" + decode(value.removePrefix("minis://")))
            value.startsWith("file://") -> {
                val withoutScheme = value.removePrefix("file://")
                // file:///a.png keeps a leading slash; file://host/a.png is not a
                // local path on Android and is refused instead of guessed.
                when {
                    withoutScheme.startsWith("/") -> ImageSource.LinuxPath(decode(withoutScheme))
                    else -> ImageSource.Refused("file:// URIs with a host are not supported; pass an absolute path or a file:// URI")
                }
            }
            value.startsWith(MEDIA_URI_PREFIX) -> ImageSource.MediaContentUri(value)
            value.startsWith("content://") -> ImageSource.Refused(
                "only the system media provider ($MEDIA_URI_PREFIX) is read directly; " +
                    "export any other content:// URI first (for photos: android-photos export --id <id>) " +
                    "and read the exported path",
            )
            value.startsWith("http://") || value.startsWith("https://") -> ImageSource.Refused(
                "read_image cannot fetch remote URLs; download the file first (for example with curl) and read the local path",
            )
            // Everything else stays the pre-existing contract: a guest path, absolute
            // or relative to the session workspace, resolved by WorkspaceFileClient.
            else -> ImageSource.LinuxPath(value)
        }
    }

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: Exception) {
        value
    }
}
