package com.openminis.app.backup

import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

/**
 * On-device proof that a backup written to the device folder really lands in Downloads/Minis Backups with its
 * exact bytes, under the exact name, and can be removed again. Android 10+ (MediaStore) only: older versions
 * need a runtime storage permission this test cannot grant.
 */
@RunWith(AndroidJUnit4::class)
class BackupDeviceStorageInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val storage = BackupDeviceStorage(context)
    private val name = "instrumented-test-${System.nanoTime()}.minisbak"
    private lateinit var source: File

    @Before
    fun setUp() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        source = File(context.cacheDir, name).apply { writeBytes(ByteArray(3 * 1024 * 1024 + 17) { (it % 251).toByte() }) }
    }

    @After
    fun tearDown() {
        if (::source.isInitialized) source.delete()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) storage.delete(name)
    }

    private fun storedBytes(): ByteArray? {
        val resolver = context.contentResolver
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf("${Environment.DIRECTORY_DOWNLOADS}/${BackupDeviceStorage.FOLDER}/", name),
            null,
        )?.use { c ->
            if (!c.moveToFirst()) return null
            val uri = android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0))
            return resolver.openInputStream(uri)?.use { it.readBytes() }
        }
        return null
    }

    @Test
    fun deliveredPackageIsInDownloadsWithItsExactBytes() {
        val progress = mutableListOf<Long>()
        val delivery = storage.deliver(source) { sent, _ -> progress.add(sent) }

        assertEquals("the stored name is the package name", name, delivery.displayName)
        assertEquals(source.length(), delivery.bytes)
        assertTrue("progress reached the total", progress.last() == source.length())
        val stored = storedBytes()
        assertTrue("the package is in Download/Minis Backups", stored != null)
        assertTrue("byte for byte", stored!!.contentEquals(source.readBytes()))
    }

    @Test
    fun deletedPackageIsGoneAndASecondDeleteSaysSo() {
        storage.deliver(source)
        assertTrue(storage.delete(name))
        assertTrue("no longer in Downloads", storedBytes() == null)
        assertFalse("nothing left to delete", storage.delete(name))
    }

    @Test
    fun refusesANameThatIsNotAPackageAndWritesNothing() {
        val bad = File(context.cacheDir, "not-a-package.txt").apply { writeText("x") }
        try {
            storage.deliver(bad)
            fail("a non-package name must be refused")
        } catch (expected: IOException) {
            // refused before anything reached shared storage
        } finally {
            bad.delete()
        }
        assertFalse(storage.delete("../escape.minisbak"))
    }
}
