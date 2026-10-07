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
        // resetLayoutForTest() falls back to the legacy host paths and drops the app context; put the
        // app's real layout back so tests that run later in this process still see it.
        UbuntuPaths.initialize(context)
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
    fun movingAFileOntoItsOwnAliasKeepsTheFile() = runBlocking {
        val payload = "keep me\n".toByteArray()
        WorkspaceFileClient.writeBytes(null, "/workspace/attachments/a.txt", payload)
        // The same file under two names: before the fix the "destination" was deleted first,
        // which was the source itself.
        runCatching {
            WorkspaceFileClient.move(null, "/workspace/attachments/a.txt", "/var/minis/attachments/a.txt")
        }
        assertArrayEquals(payload, WorkspaceFileClient.readAll(null, "/workspace/attachments/a.txt"))
    }

    @Test
    fun copyingADirectoryIntoItselfThroughAnAliasIsRefused() = runBlocking {
        WorkspaceFileClient.writeBytes(null, "/workspace/data/one.txt", "1".toByteArray())
        val failure = runCatching {
            WorkspaceFileClient.copy(null, "/workspace", "/var/minis/attachments/copy")
        }.exceptionOrNull()
        assertTrue("copying into itself must fail, not recurse", failure != null)
        assertTrue(!File(root, "workspace/attachments/copy/attachments").exists())
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

    @Test
    fun writingOverAnExistingFileReplacesItsContent() = runBlocking {
        WorkspaceFileClient.writeBytes(null, "/workspace/f.txt", "old".toByteArray())
        WorkspaceFileClient.writeBytes(null, "/workspace/f.txt", "new content".toByteArray())
        assertArrayEquals("new content".toByteArray(), WorkspaceFileClient.readAll(null, "/workspace/f.txt"))
        assertTrue("no temporary file is left behind", File(root, "workspace").list().orEmpty().none { it != "f.txt" && it.contains("f.txt") })
    }

    @Test
    fun writingOverADirectoryIsRefusedAndTheTreeSurvives() = runBlocking {
        WorkspaceFileClient.writeBytes(null, "/workspace/project/src/a.txt", "A".toByteArray())
        WorkspaceFileClient.writeBytes(null, "/workspace/project/b.txt", "B".toByteArray())
        val failure = runCatching {
            WorkspaceFileClient.writeBytes(null, "/workspace/project", "oops".toByteArray())
        }.exceptionOrNull()
        assertTrue("overwriting a directory must fail", failure is WorkspaceFileClient.Failure)
        assertArrayEquals("A".toByteArray(), WorkspaceFileClient.readAll(null, "/workspace/project/src/a.txt"))
        assertArrayEquals("B".toByteArray(), WorkspaceFileClient.readAll(null, "/workspace/project/b.txt"))
    }

    @Test
    fun movingOntoADirectoryIsRefusedAndBothSidesSurvive() = runBlocking {
        WorkspaceFileClient.writeBytes(null, "/workspace/src.txt", "S".toByteArray())
        WorkspaceFileClient.writeBytes(null, "/workspace/dest/keep.txt", "K".toByteArray())
        val failure = runCatching { WorkspaceFileClient.move(null, "/workspace/src.txt", "/workspace/dest") }.exceptionOrNull()
        assertTrue("moving onto a directory must fail", failure is WorkspaceFileClient.Failure)
        assertArrayEquals("S".toByteArray(), WorkspaceFileClient.readAll(null, "/workspace/src.txt"))
        assertArrayEquals("K".toByteArray(), WorkspaceFileClient.readAll(null, "/workspace/dest/keep.txt"))
    }

    @Test
    fun movingAFileOverAnotherFileReplacesIt() = runBlocking {
        WorkspaceFileClient.writeBytes(null, "/workspace/a.txt", "from".toByteArray())
        WorkspaceFileClient.writeBytes(null, "/workspace/b.txt", "to".toByteArray())
        WorkspaceFileClient.move(null, "/workspace/a.txt", "/workspace/b.txt")
        assertArrayEquals("from".toByteArray(), WorkspaceFileClient.readAll(null, "/workspace/b.txt"))
        assertTrue(runCatching { WorkspaceFileClient.readAll(null, "/workspace/a.txt") }.isFailure)
    }

    @Test
    fun copyingAFileOverAnotherFileReplacesIt() = runBlocking {
        WorkspaceFileClient.writeBytes(null, "/workspace/a.txt", "from".toByteArray())
        WorkspaceFileClient.writeBytes(null, "/workspace/b.txt", "to".toByteArray())
        WorkspaceFileClient.copy(null, "/workspace/a.txt", "/workspace/b.txt")
        assertArrayEquals("from".toByteArray(), WorkspaceFileClient.readAll(null, "/workspace/b.txt"))
        assertArrayEquals("from".toByteArray(), WorkspaceFileClient.readAll(null, "/workspace/a.txt"))
    }
}
