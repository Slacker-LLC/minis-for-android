package com.openminis.app.runtime

import com.openminis.app.data.MountedFoldersStore

/**
 * Maps user-selected SAF folders into the current Ubuntu runtime contract.
 * Resolved host paths become bind-mount inputs for the minisd-owned mount
 * namespace; guest paths live under `/var/minis/mounts/<name>`. This layer
 * also enforces the user-visible read-only policy before file tools write.
 */
object ExternalMountCoordinator {

    /** Linux prefix under which all bind-mounted external folders live. */
    private const val MOUNTS_PREFIX = "/var/minis/mounts/"

    /**
     * Throws [ReadOnlyMountException] when [linuxPath] is inside an
     * effectively read-only mount. Paths outside `/var/minis/mounts/` and
     * paths inside writable mounts pass through silently.
     */
    fun requireWritable(linuxPath: String, store: MountedFoldersStore) {
        if (isLinuxPathUnderReadOnlyMount(linuxPath, store)) {
            throw ReadOnlyMountException(linuxPath)
        }
    }

    /**
     * True when [linuxPath] is `/var/minis/mounts/<name>/...` AND the
     * matching entry in [store] is currently locked (either OS-level
     * non-writable or user-toggled off via `Allow writes`).
     *
     * Returns false for paths outside the mounts tree, for unknown mount
     * names (defensive: an unknown name means there's no enforceable
     * read-only contract — let other layers decide), and for fully
     * writable mounts.
     */
    fun isLinuxPathUnderReadOnlyMount(linuxPath: String, store: MountedFoldersStore): Boolean {
        if (!linuxPath.startsWith(MOUNTS_PREFIX)) return false
        val tail = linuxPath.substring(MOUNTS_PREFIX.length)
        // Match `<name>` or `<name>/...` exactly — don't treat
        // `/var/minis/mounts/foobar` as inside a mount named `foo`.
        val name = tail.substringBefore('/')
        if (name.isEmpty()) return false
        val entry = store.entries.value.firstOrNull { it.name == name } ?: return false
        return !entry.effectiveWritable
    }

    /**
     * Snapshot of bind-mount specs supplied to the Ubuntu/minisd runtime. Only entries with a
     * resolved POSIX path are returned — entries whose URI resolution
     * failed (e.g. cloud provider, removable volume unmounted) silently
     * drop out of the snapshot until their state changes.
     *
     * Returns `(linuxDir, hostPath)` pairs.
     */
    fun bindMountSpecs(store: MountedFoldersStore): List<Pair<String, String>> =
        store.entries.value
            .mapNotNull { e ->
                val host = e.resolvedHostPath ?: return@mapNotNull null
                "$MOUNTS_PREFIX${e.name}" to host
            }
}

/**
 * Thrown when a write tool is asked to modify a path inside a locked
 * external mount. The message is intentionally user-facing and matches
 * the wording surfaced in iOS so chat transcripts read the same way
 * across platforms.
 */
class ReadOnlyMountException(val linuxPath: String) : Exception(
    "$linuxPath is inside a read-only mounted folder and cannot be modified. " +
        "Toggle writability in Settings → Mount External Folders if this is a mistake.",
)
