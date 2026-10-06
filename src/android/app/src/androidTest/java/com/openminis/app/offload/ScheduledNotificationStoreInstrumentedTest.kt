package com.openminis.app.offload

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
class ScheduledNotificationStoreInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var store: ScheduledNotificationStore

    @Before
    fun setUp() {
        store = ScheduledNotificationStore(context)
        store.clear()
    }

    @After
    fun tearDown() = store.clear()

    private fun entry(id: String, at: Long = System.currentTimeMillis() + 600_000, body: String = "b") =
        JSONObject().put("id", id).put("title", "t").put("body", body).put("trigger_at_ms", at)

    @Test
    fun scheduling_the_same_id_twice_leaves_one_entry_with_the_latest_content() {
        store.add(entry("same", body = "first"))
        store.add(entry("same", body = "second"))
        assertEquals(1, store.loadAll().length())
        assertEquals("second", store.get("same")!!.getString("body"))
        // One fire consumes it: nothing is left as a phantom pending item.
        store.remove("same")
        assertEquals(0, store.loadAll().length())
    }

    @Test
    fun concurrent_adds_from_separate_instances_all_survive() {
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(40)
        repeat(40) { i ->
            pool.execute {
                start.await()
                ScheduledNotificationStore(context).add(entry("n$i"))
                done.countDown()
            }
        }
        start.countDown()
        done.await()
        pool.shutdown()
        assertEquals(40, store.loadAll().length())
    }

    @Test
    fun an_add_racing_a_remove_does_not_resurrect_the_removed_entry() {
        store.add(entry("a"))
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val done = CountDownLatch(21)
        pool.execute { start.await(); ScheduledNotificationStore(context).remove("a"); done.countDown() }
        repeat(20) { i ->
            pool.execute { start.await(); ScheduledNotificationStore(context).add(entry("b$i")); done.countDown() }
        }
        start.countDown()
        done.await()
        pool.shutdown()
        assertNull(store.get("a"))
        assertEquals(20, store.loadAll().length())
    }

    @Test
    fun a_query_does_not_drop_an_entry_that_is_only_a_little_late() {
        store.add(entry("late", at = System.currentTimeMillis() - 5 * 60_000))
        store.add(entry("lost", at = System.currentTimeMillis() - 3 * 3600_000))
        store.sweepExpired()
        assertNotNull(store.get("late"))
        assertNull(store.get("lost"))
    }
}
