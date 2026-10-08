package com.openminis.app.ui.sandbox

/**
 * What "Browse chat files" shows. A chat's folders (`workspace`, `attachments`, `offloads`, `browser`) always
 * exist, and the three that are also mounted inside `workspace` appear there once more as empty folders so the
 * guest can reach them. Listing all of that made an empty chat look full of files; the chat's root now shows only
 * the folders that hold something, and the mount points are left out of `workspace`.
 */
internal object ChatFilesScope {
    const val ROOT = "/var/minis"
    val FOLDERS = listOf("workspace", "attachments", "offloads", "browser")

    private const val WORKSPACE = "$ROOT/workspace"
    private val MOUNTED_INSIDE_WORKSPACE = setOf("attachments", "offloads", "browser")

    /** A folder of `workspace` that is only the mount point of one of the chat's other folders. */
    fun isMountPoint(directory: String, name: String, type: String): Boolean =
        directory == WORKSPACE && type == "dir" && name in MOUNTED_INSIDE_WORKSPACE
}
