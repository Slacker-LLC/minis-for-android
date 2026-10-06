package com.openminis.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FileMentionIndexCacheTest {
    @Before fun setMain() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun resetMain() = Dispatchers.resetMain()

    @Test
    fun `a cache is reused only for the same session, inside the TTL, with entries`() {
        assertTrue(mentionCacheUsable(sameSession = true, fresh = true, hasEntries = true))
        assertFalse(mentionCacheUsable(sameSession = false, fresh = true, hasEntries = true))
        assertFalse(mentionCacheUsable(sameSession = true, fresh = false, hasEntries = true))
        assertFalse(mentionCacheUsable(sameSession = true, fresh = true, hasEntries = false))
    }

    @Test
    fun `an unsafe session id scans nothing and never marks the scan as running afterwards`() = runBlocking {
        val index = FileMentionIndex(scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined))
        index.refreshIfNeeded("../escape")
        // Unconfined: the scan has already run to completion.
        assertFalse(index.isScanning.value)
        assertTrue(index.entries.value.isEmpty())
    }

    @Test
    fun `a changed mount set is asked for again even when the cache is fresh`() = runBlocking {
        val asked = CompletableDeferred<Unit>()
        var calls = 0
        val index = FileMentionIndex(
            mountsProvider = {
                calls++
                if (calls >= 2) asked.complete(Unit)
                emptyList()
            },
            scope = kotlinx.coroutines.CoroutineScope(Dispatchers.Unconfined),
        )
        index.refreshIfNeeded("../escape") // scan: reads the mounts key once
        index.refreshIfNeeded("../escape") // nothing cached (no entries) -> scans again, reads it again
        withTimeout(2_000) { asked.await() }
        assertTrue(calls >= 2)
    }
}
