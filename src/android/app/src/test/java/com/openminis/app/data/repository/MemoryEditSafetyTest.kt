package com.openminis.app.data.repository

import com.openminis.app.data.repository.MemoryRepository.EditRead
import com.openminis.app.runtime.ubuntu.UbuntuPaths
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MemoryEditSafetyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var repo: MemoryRepository
    private lateinit var memoryDir: File

    @Before
    fun setUp() {
        UbuntuPaths.useLayoutForTest(tmp.root)
        UbuntuPaths.ensureBaseDirs()
        memoryDir = File(UbuntuPaths.hostMemory)
        repo = MemoryRepository()
    }

    @After
    fun tearDown() {
        UbuntuPaths.resetLayoutForTest()
    }

    @Test
    fun anExistingFileIsLoadedAndAMissingOneIsSaidToBeMissing() {
        File(memoryDir, "2026-10-06.md").writeText("entry A")
        assertEquals(EditRead.Loaded("entry A"), repo.readFileForEdit("2026-10-06.md"))
        assertEquals(EditRead.Missing, repo.readFileForEdit("not-yet.md"))
    }

    @Test
    fun anUnreadableOrInvalidNameIsAFailureNotAnEmptyFile() {
        // A directory where the file should be: reading it fails, and must not look like "".
        File(memoryDir, "broken.md").mkdirs()
        assertEquals(EditRead.Failed, repo.readFileForEdit("broken.md"))
        assertEquals(EditRead.Failed, repo.readFileForEdit("../escape.md"))
    }

    @Test
    fun saveGoesThroughWhenTheFileIsUnchanged() {
        File(memoryDir, "day.md").writeText("A")
        val opened = repo.readFileForEdit("day.md")
        assertTrue(repo.saveFileIfUnchanged("day.md", "A edited", opened))
        assertEquals("A edited", File(memoryDir, "day.md").readText())
    }

    @Test
    fun anEntryWrittenMeanwhileIsNotOverwrittenByTheOldDraft() {
        File(memoryDir, "day.md").writeText("A")
        val opened = repo.readFileForEdit("day.md")
        // An agent's memory_write lands while the editor is open.
        File(memoryDir, "day.md").writeText("B\nA")

        assertFalse(repo.saveFileIfUnchanged("day.md", "A edited", opened))
        assertEquals("B\nA", File(memoryDir, "day.md").readText())
    }

    @Test
    fun aFileCreatedMeanwhileBlocksTheSaveOfANewOne() {
        val opened = repo.readFileForEdit("new.md")
        assertEquals(EditRead.Missing, opened)
        File(memoryDir, "new.md").writeText("someone else")
        assertFalse(repo.saveFileIfUnchanged("new.md", "mine", opened))
        assertEquals("someone else", File(memoryDir, "new.md").readText())
    }

    @Test
    fun aFailedReadNeverAllowsASave() {
        File(memoryDir, "broken.md").mkdirs()
        assertFalse(repo.saveFileIfUnchanged("broken.md", "x", EditRead.Failed))
    }
}
