package com.openminis.app.data

import com.openminis.app.data.MountedFoldersStore.Entry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MountedFoldersPersistenceTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun mount(name: String, uri: String) =
        Entry(name = name, sourceDisplayName = name, treeUri = uri)

    @Test
    fun `an atomic write replaces the content and leaves no temporary file`() {
        val file = File(tmp.root, "mounted-folders.json")
        file.writeText("old")
        assertTrue(writeTextAtomically(file, "new"))
        assertEquals("new", file.readText())
        assertEquals(listOf("mounted-folders.json"), tmp.root.list()!!.toList())
    }

    @Test
    fun `a write that cannot complete reports failure and keeps the previous content`() {
        // The target is a non-empty directory, so the final rename cannot replace it.
        val target = File(tmp.root, "mounted-folders.json").apply { mkdirs() }
        File(target, "keep").writeText("x")
        assertFalse(writeTextAtomically(target, "new"))
        assertTrue(File(target, "keep").isFile)
        assertFalse("the temporary file is cleaned up", File(tmp.root, "mounted-folders.json.tmp").exists())
    }

    @Test
    fun `a missing parent directory is a failure, not an exception`() {
        assertFalse(writeTextAtomically(File(tmp.root, "no/such/dir/file.json"), "x"))
    }

    @Test
    fun `a grant shared by two mounts of one tree is not released with the first`() {
        val a = mount("a", "content://tree/x")
        val b = mount("b", "content://tree/x")
        val other = mount("c", "content://tree/y")
        assertFalse("b still uses the tree", grantReleasable(listOf(b, other), a.treeUri))
        assertTrue("nothing uses it once both are gone", grantReleasable(listOf(other), a.treeUri))
        assertTrue(grantReleasable(emptyList(), a.treeUri))
    }
}
