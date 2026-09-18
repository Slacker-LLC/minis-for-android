package com.openminis.app.runtime.files

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.runtime.ubuntu.UbuntuPaths
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.file.Files

/** Device proof that the App-owned file API works on Android's actual NIO provider. */
@RunWith(AndroidJUnit4::class)
class SecureFileAccessInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.filesDir, "minis-secure-instrumented")

    init {
        root.deleteRecursively()
        check(root.mkdirs()) { "cannot create instrumentation root" }
        UbuntuPaths.useLayoutForTest(root)
        check(UbuntuPaths.ensureBaseDirs()) { "cannot create secure test layout" }
    }

    @After
    fun tearDown() {
        UbuntuPaths.resetLayoutForTest()
        root.deleteRecursively()
    }

    @Test
    fun appOwnedFileCanBeWrittenAndReadThroughSecureApi() = runBlocking {
        val payload = "android-secure-file\n".toByteArray()
        WorkspaceFileClient.writeBytes(null, "/workspace/hello.txt", payload)
        assertArrayEquals(payload, WorkspaceFileClient.readAll(null, "/workspace/hello.txt"))
        assertTrue(SecureFileAccess.probeWritable(File(root, "workspace")))
    }

    @Test
    fun symlinkEscapeIsRejectedWithoutTouchingOutsideFile() = runBlocking {
        val outside = File(root.parentFile, "minis-secure-outside").apply {
            deleteRecursively()
            check(mkdirs())
        }
        val marker = File(outside, "marker.txt").apply { writeText("keep") }
        try {
            try {
                Files.createSymbolicLink(
                    File(root, "workspace/escape").toPath(),
                    outside.toPath(),
                )
            } catch (error: Exception) {
                assumeNoException("device filesystem does not permit test symlinks", error)
            }

            try {
                WorkspaceFileClient.readAll(null, "/workspace/escape/marker.txt")
                throw AssertionError("symlink path unexpectedly read")
            } catch (error: WorkspaceFileClient.Failure) {
                // Expected fail-closed result.
                assertTrue(error.code == "NOT_FILE" || error.code == "IO_ERROR")
            } catch (_: java.nio.file.FileSystemException) {
                // ELOOP is also a valid no-follow result on some providers.
            }
            assertEquals("keep", marker.readText())
        } finally {
            outside.deleteRecursively()
        }
    }
}
