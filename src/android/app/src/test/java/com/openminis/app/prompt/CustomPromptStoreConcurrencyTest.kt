package com.openminis.app.prompt

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CustomPromptStoreConcurrencyTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun context(): Context {
        val dir = tmp.newFolder("files")
        return object : ContextWrapper(null) {
            override fun getFilesDir(): File = dir
        }
    }

    private fun onDisk(context: Context) =
        File(File(context.filesDir, PromptModuleStore.OVERRIDE_DIR_NAME), "custom.md").takeIf { it.isFile }?.readText()

    @Test
    fun `concurrent saves leave the cache and the file on the same text`() {
        val context = context()
        CustomPromptStore.clear(context)
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        repeat(8) { worker ->
            pool.execute {
                start.await()
                repeat(60) { i -> CustomPromptStore.save(context, "worker $worker save $i") }
                done.countDown()
            }
        }
        start.countDown()
        assertTrue(done.await(60, TimeUnit.SECONDS))
        pool.shutdown()
        // Whichever save won last, what the model is given must be what a restart would read.
        assertEquals(onDisk(context), CustomPromptStore.text(context))
        assertEquals(CustomPromptStore.text(context), CustomPromptStore.textFlow.value.ifEmpty { null })
    }

    @Test
    fun `saving blank text clears both the file and the cache`() {
        val context = context()
        CustomPromptStore.save(context, "  keep me  ")
        assertEquals("keep me", CustomPromptStore.text(context))
        assertEquals("keep me", onDisk(context))
        CustomPromptStore.save(context, "   ")
        assertNull(CustomPromptStore.text(context))
        assertNull(onDisk(context))
    }
}
