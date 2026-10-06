package com.openminis.app.tools.internal

import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlinx.coroutines.launch
import org.junit.Test

class FileMutationQueueTest {
    @Test fun sameFileMutationsAreSerialized() {
        val f = File.createTempFile("minis-queue", ".txt")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val order = Collections.synchronizedList(mutableListOf<String>())
        val pool = Executors.newFixedThreadPool(2)
        try {
            val a = pool.submit {
                FileMutationQueue.withFile(f) {
                    order += "a-enter"
                    entered.countDown()
                    release.await(2, TimeUnit.SECONDS)
                    order += "a-exit"
                }
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val b = pool.submit {
                FileMutationQueue.withFile(f) {
                    order += "b-enter"
                    order += "b-exit"
                }
            }
            Thread.sleep(80)
            assertEquals(listOf("a-enter"), order.toList())
            release.countDown()
            a.get(2, TimeUnit.SECONDS); b.get(2, TimeUnit.SECONDS)
            assertEquals(listOf("a-enter", "a-exit", "b-enter", "b-exit"), order.toList())
        } finally {
            release.countDown(); pool.shutdownNow(); f.delete()
        }
    }

    @Test fun aliasesOfOneSessionFileShareALockKey() = kotlinx.coroutines.runBlocking {
        val a = FileMutationQueue.keyFor("s1", "/workspace/dir/x.txt")
        assertEquals(a, FileMutationQueue.keyFor("s1", "/var/minis/workspace/dir/x.txt"))
        assertEquals(a, FileMutationQueue.keyFor("s1", "dir/x.txt"))
        // Another file, and the same name in another session, do not.
        assertTrue(a != FileMutationQueue.keyFor("s1", "/workspace/dir/y.txt"))
        assertTrue(a != FileMutationQueue.keyFor("s2", "/workspace/dir/x.txt"))
    }

    @Test fun suspendLocksAreDroppedWhenNobodyHoldsThem() = kotlinx.coroutines.runBlocking {
        val before = FileMutationQueue.heldKeysForTest()
        FileMutationQueue.withKey("reclaim-test") { }
        assertEquals(before, FileMutationQueue.heldKeysForTest())
    }

    @Test fun sameKeyBlocksRunOneAtATime() = kotlinx.coroutines.runBlocking {
        var inside = 0
        var maxInside = 0
        kotlinx.coroutines.coroutineScope {
            repeat(6) {
                launch(kotlinx.coroutines.Dispatchers.Default) {
                    FileMutationQueue.withKey("serial-test") {
                        inside++
                        maxInside = maxOf(maxInside, inside)
                        kotlinx.coroutines.delay(5)
                        inside--
                    }
                }
            }
        }
        assertEquals(1, maxInside)
    }
}
