package com.openminis.app.runtime.files

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SafeFileTreeTest {
    @Test
    fun `recursive delete removes symlink but never follows its target`() {
        val temp = Files.createTempDirectory("minis-safe-delete")
        val outside = Files.createTempDirectory("minis-safe-delete-outside")
        try {
            val marker = outside.resolve("keep.txt")
            Files.write(marker, byteArrayOf(1, 2, 3))
            val root = temp.resolve("root")
            Files.createDirectories(root)
            Files.createSymbolicLink(root.resolve("escape"), outside)

            assertTrue(SafeFileTree.deleteRecursively(root.toFile()))
            assertFalse(Files.exists(root))
            assertTrue(Files.exists(marker))
        } finally {
            SafeFileTree.deleteRecursively(temp.toFile())
            SafeFileTree.deleteRecursively(outside.toFile())
        }
    }

    @Test
    fun `nofollow existence sees broken symlink`() {
        val temp = Files.createTempDirectory("minis-safe-exists")
        try {
            val link = temp.resolve("broken")
            Files.createSymbolicLink(link, temp.resolve("missing"))
            assertTrue(SafeFileTree.existsNoFollow(link.toFile()))
            assertTrue(SafeFileTree.isSymbolicLink(link.toFile()))
        } finally {
            SafeFileTree.deleteRecursively(temp.toFile())
        }
    }
}
