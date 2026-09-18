package com.openminis.app.runtime.files

import com.openminis.app.runtime.ubuntu.UbuntuPaths
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeNoException
import org.junit.Test
import java.nio.file.FileSystemException
import java.nio.file.Files

class SecureFileAccessTest {
    @Test
    fun `read rejects a symlink component without touching its target`() = runBlocking {
        val root = Files.createTempDirectory("minis-secure-file").toFile()
        val outside = Files.createTempDirectory("minis-secure-file-outside").toFile()
        try {
            val workspace = root.resolve("workspace").apply { mkdirs() }
            val marker = outside.resolve("secret.txt").apply { writeText("outside") }
            try {
                Files.createSymbolicLink(workspace.resolve("escape").toPath(), outside.toPath())
            } catch (error: Exception) {
                if (!symlinksUnavailable(error)) throw error
                assumeNoException("Symbolic links are unavailable on this test host", error)
            }
            UbuntuPaths.useLayoutForTest(root)
            val path = UbuntuPaths.resolveSecureForFileAccess(null, "/workspace/escape/secret.txt")!!
            try {
                SecureFileAccess.readAll(path, 1024)
                fail("symlink path unexpectedly read")
            } catch (error: WorkspaceFileClient.Failure) {
                assertEquals("NOT_FILE", error.code)
            } catch (_: FileSystemException) {
                // The host provider may reject a NOFOLLOW directory walk with ELOOP.
            }
            assertEquals("outside", marker.readText())
        } finally {
            UbuntuPaths.resetLayoutForTest()
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun `delete removes a symlink entry but not its target`() = runBlocking {
        val root = Files.createTempDirectory("minis-secure-delete").toFile()
        val outside = Files.createTempDirectory("minis-secure-delete-outside").toFile()
        try {
            val workspace = root.resolve("workspace").apply { mkdirs() }
            val marker = outside.resolve("keep.txt").apply { writeText("keep") }
            try {
                Files.createSymbolicLink(workspace.resolve("escape").toPath(), outside.toPath())
            } catch (error: Exception) {
                if (!symlinksUnavailable(error)) throw error
                assumeNoException("Symbolic links are unavailable on this test host", error)
            }
            UbuntuPaths.useLayoutForTest(root)
            val path = UbuntuPaths.resolveSecureForFileAccess(null, "/workspace/escape")!!
            assertTrue(SecureFileAccess.delete(path))
            assertTrue(marker.exists())
        } finally {
            UbuntuPaths.resetLayoutForTest()
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    @Test
    fun `a symlink trusted root is rejected without reading its target`() = runBlocking {
        val root = Files.createTempDirectory("minis-secure-root").toFile()
        val outside = Files.createTempDirectory("minis-secure-root-outside").toFile()
        try {
            val marker = outside.resolve("secret.txt").apply { writeText("outside") }
            val workspace = root.resolve("workspace").toPath()
            try {
                Files.createSymbolicLink(workspace, outside.toPath())
            } catch (error: Exception) {
                if (!symlinksUnavailable(error)) throw error
                assumeNoException("Symbolic links are unavailable on this test host", error)
            }
            UbuntuPaths.useLayoutForTest(root)
            val path = UbuntuPaths.resolveSecureForFileAccess(null, "/workspace/secret.txt")!!
            try {
                SecureFileAccess.readAll(path, 1024)
                fail("symlink trusted root unexpectedly read")
            } catch (error: WorkspaceFileClient.Failure) {
                assertTrue(error.code == "NOT_DIR" || error.code == "IO_ERROR")
            } catch (_: FileSystemException) {
                // The host provider may report ELOOP while opening the root.
            }
            assertEquals("outside", marker.readText())
        } finally {
            UbuntuPaths.resetLayoutForTest()
            root.deleteRecursively()
            outside.deleteRecursively()
        }
    }

    private fun symlinksUnavailable(error: Throwable): Boolean =
        error is UnsupportedOperationException ||
            error is SecurityException ||
            (System.getProperty("os.name").orEmpty().startsWith("Windows") && error is FileSystemException)
}
