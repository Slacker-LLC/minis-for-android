package com.openminis.app.tools.internal

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
    private val suspendEntries = ConcurrentHashMap<String, Mutex>()
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

    suspend fun <T> withKey(key: String, block: suspend () -> T): T {
        val mutex = suspendEntries.getOrPut(key) { Mutex() }
        return mutex.withMutexLock {
            block()
        }
    }
}
