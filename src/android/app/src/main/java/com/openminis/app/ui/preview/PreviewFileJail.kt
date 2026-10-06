package com.openminis.app.ui.preview

import com.openminis.app.runtime.ubuntu.UbuntuPaths
import java.io.File

/**
 * Which `file://` paths the in-app HTML preview may load. The preview WebView has file access
 * (and file-to-file script access) switched on so a demo can load `<script src="game.js">` or
 * `fetch("data.json")` from its own folder; this keeps that working while closing off the rest of
 * the app's private storage (databases, preferences, keys).
 */
internal object PreviewFileJail {

    /** The guest's data folders, plus the folder the previewed file itself was opened from. */
    fun roots(initialUrl: String): List<File> {
        val guest = listOf(
            UbuntuPaths.hostSessions,
            UbuntuPaths.hostWorkspace,
            UbuntuPaths.hostShared,
            UbuntuPaths.hostHome,
            UbuntuPaths.hostMemory,
            UbuntuPaths.hostSkills,
        ).map(::File)
        val opened = initialUrl.takeIf { it.startsWith("file://") }
            ?.removePrefix("file://")
            ?.let { File(java.net.URLDecoder.decode(it, "UTF-8")).parentFile }
        return guest + listOfNotNull(opened)
    }

    /** True when [path], with `..` and symlinks resolved, is one of [roots] or below it. */
    fun allows(path: String, roots: List<File>): Boolean {
        if (path.isEmpty()) return false
        val target = runCatching { File(path).canonicalFile }.getOrNull() ?: return false
        return roots.any { root ->
            val base = runCatching { root.canonicalFile }.getOrNull() ?: return@any false
            target == base || target.path.startsWith(base.path + File.separator)
        }
    }
}
