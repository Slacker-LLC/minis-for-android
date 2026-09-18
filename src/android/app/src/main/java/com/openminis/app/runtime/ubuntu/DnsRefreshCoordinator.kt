package com.openminis.app.runtime.ubuntu

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Read the active resolver only after the preceding write has finished. */
internal class DnsRefreshCoordinator {
    private val mutex = Mutex()

    suspend fun refresh(read: () -> List<String>, write: suspend (List<String>) -> Boolean): Boolean =
        mutex.withLock { write(read()) }
}
