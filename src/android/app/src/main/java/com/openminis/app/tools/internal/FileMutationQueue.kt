package com.openminis.app.tools.internal

import com.openminis.app.runtime.ubuntu.UbuntuPaths
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock as withMutexLock

/**
 * Serializes mutations targeting the same canonical host path while allowing
 * unrelated files to proceed concurrently. This mirrors Pi's per-file mutation
 * queue and prevents concurrent file_write/file_edit calls from overwriting
 * each other's view of the file.
 */
object FileMutationQueue {
    private data class Entry(val lock: ReentrantLock = ReentrantLock(true), var users: Int = 0)
    private val entries = ConcurrentHashMap<String, Entry>()
    private class SuspendEntry(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val suspendEntries = ConcurrentHashMap<String, SuspendEntry>()
    private val guard = Any()

    fun <T> withFile(file: File, block: () -> T): T {
        val key = runCatching { file.canonicalPath }.getOrElse { file.absolutePath }
        val entry = synchronized(guard) {
            val current = entries[key] ?: Entry().also { entries[key] = it }
            current.users += 1
            current
        }
        try {
            return entry.lock.withLock(block)
        } finally {
            synchronized(guard) {
                entry.users -= 1
                if (entry.users == 0 && !entry.lock.isLocked && !entry.lock.hasQueuedThreads()) {
                    entries.remove(key, entry)
                }
            }
        }
    }

    /**
     * Lock key for the file a tool call really targets. `/workspace/x` and
     * `/var/minis/workspace/x` are the same file, so the resolved storage
     * identity is the key; an unresolvable path falls back to the raw text
     * (the call is then refused by the file client anyway).
     */
    suspend fun keyFor(sessionId: String, path: String): String {
        val resolved = UbuntuPaths.resolveSecureForFileAccess(sessionId, path)
            ?: return "$sessionId\u0000$path"
        return resolved.root.path + "\u0000" + resolved.components.joinToString("/")
    }

    suspend fun <T> withKey(key: String, block: suspend () -> T): T {
        val entry = synchronized(guard) {
            suspendEntries.getOrPut(key) { SuspendEntry() }.also { it.users += 1 }
        }
        try {
            return entry.mutex.withMutexLock { block() }
        } finally {
            synchronized(guard) {
                entry.users -= 1
                if (entry.users == 0) suspendEntries.remove(key, entry)
            }
        }
    }

    internal fun heldKeysForTest(): Int = suspendEntries.size
}
