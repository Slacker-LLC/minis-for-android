package com.openminis.app.ui.preview

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PreviewFileJailTest {
    private lateinit var base: File
    private lateinit var workspace: File
    private lateinit var secrets: File

    @Before
    fun setUp() {
        base = Files.createTempDirectory("preview-jail").toFile()
        workspace = File(base, "minis/workspace").apply { mkdirs() }
        secrets = File(base, "databases").apply { mkdirs() }
        File(workspace, "index.html").writeText("<html/>")
        File(workspace, "sub").mkdirs()
        File(secrets, "keys.db").writeText("secret")
    }

    @After
    fun tearDown() {
        base.deleteRecursively()
    }

    private fun jail() = listOf(workspace)

    @Test
    fun aFileInsideAnAllowedFolderIsAllowed() {
        assertTrue(PreviewFileJail.allows(File(workspace, "index.html").path, jail()))
        assertTrue(PreviewFileJail.allows(File(workspace, "sub/new.js").path, jail()))
        assertTrue(PreviewFileJail.allows(workspace.path, jail()))
    }

    @Test
    fun privateAppFilesOutsideAreRefused() {
        assertFalse(PreviewFileJail.allows(File(secrets, "keys.db").path, jail()))
        assertFalse(PreviewFileJail.allows("/etc/passwd", jail()))
        assertFalse(PreviewFileJail.allows("", jail()))
    }

    @Test
    fun dotDotCannotEscape() {
        assertFalse(PreviewFileJail.allows(File(workspace, "../../databases/keys.db").path, jail()))
        assertFalse(PreviewFileJail.allows(File(workspace, "sub/../../../databases/keys.db").path, jail()))
    }

    @Test
    fun aSiblingWithTheSamePrefixIsNotInside() {
        val evil = File(base, "minis/workspace-evil").apply { mkdirs() }
        File(evil, "x").writeText("x")
        assertFalse(PreviewFileJail.allows(File(evil, "x").path, jail()))
    }

    @Test
    fun aSymlinkOutOfTheFolderIsRefused() {
        val link = File(workspace, "escape")
        val made = runCatching { Files.createSymbolicLink(link.toPath(), secrets.toPath()) }.isSuccess
        assumeTrue("symlinks unavailable here", made)
        assertFalse(PreviewFileJail.allows(File(link, "keys.db").path, jail()))
    }

    @Test
    fun theFolderTheFileWasOpenedFromIsAllowed() {
        val staged = File(base, "cache/pinned-html").apply { mkdirs() }
        val roots = PreviewFileJail.roots("file://${File(staged, "abc-page.html").path}")
        assertTrue(roots.any { it.path == staged.path })
        assertEquals(true, PreviewFileJail.allows(File(staged, "asset.js").path, roots))
        assertFalse(PreviewFileJail.allows(File(secrets, "keys.db").path, roots))
    }
}
