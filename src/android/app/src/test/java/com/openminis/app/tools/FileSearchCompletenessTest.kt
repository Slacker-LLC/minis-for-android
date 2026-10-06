package com.openminis.app.tools

import com.openminis.app.runtime.ubuntu.UbuntuPaths
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import com.openminis.app.tools.runtime.TestContext
import java.io.File

class FileSearchCompletenessTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val session = "t1"
    private lateinit var workspace: File

    @Before
    fun setUp() {
        UbuntuPaths.useLayoutForTest(tmp.root)
        UbuntuPaths.ensureBaseDirs()
        workspace = File(UbuntuPaths.hostSessions, "$session/workspace").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        UbuntuPaths.resetLayoutForTest()
    }

    private fun search(name: String, limit: Int = 100, recursive: Boolean = true): JSONObject = runBlocking {
        JSONObject(
            LinuxFileOps.search(session, name, "/workspace", null, recursive, limit, TestContext.dummy()).output,
        )
    }

    private fun grep(path: String, pattern: String): JSONObject = runBlocking {
        JSONObject(LinuxFileOps.grep(session, path, pattern, 0, 50, TestContext.dummy()).output)
    }

    // ---- search (RU08) -------------------------------------------------

    @Test
    fun aTargetBehindManyNonMatchingFilesIsStillFound() {
        repeat(150) { File(workspace, "a-filler-%03d.txt".format(it)).writeText("x") }
        File(workspace, "z-target.md").writeText("x")

        val out = search("target", limit = 100)

        assertEquals(1, out.getInt("count"))
        assertTrue(out.getJSONArray("results").getJSONObject(0).getString("path").endsWith("z-target.md"))
        assertFalse(out.getBoolean("truncated"))
    }

    @Test
    fun theTargetInASubdirectoryIsReachedThroughNonMatchingTopLevelEntries() {
        repeat(120) { File(workspace, "f$it.txt").writeText("x") }
        File(workspace, "deep/er").mkdirs()
        File(workspace, "deep/er/needle.log").writeText("x")

        assertEquals(1, search("needle").getInt("count"))
    }

    @Test
    fun theResultLimitCountsMatchesAndIsReportedAsTruncation() {
        repeat(30) { File(workspace, "match-$it.txt").writeText("x") }

        val out = search("match", limit = 10)

        assertEquals(10, out.getInt("count"))
        assertTrue("stopping at the limit must be visible", out.getBoolean("truncated"))
    }

    @Test
    fun noMatchInAFullyScannedTreeIsACompleteNegative() {
        File(workspace, "a.txt").writeText("x")
        val out = search("zzz")
        assertEquals(0, out.getInt("count"))
        assertFalse(out.getBoolean("truncated"))
        assertTrue(out.getInt("scanned") >= 1)
    }

    // ---- grep (RU09) ---------------------------------------------------

    @Test
    fun aMatchInAnOrdinaryFileIsReportedComplete() {
        File(workspace, "notes.txt").writeText("alpha\nneedle here\nomega")
        val out = grep("/workspace", "needle")
        assertEquals(1, out.getInt("count"))
        assertTrue(out.getBoolean("complete"))
    }

    @Test
    fun anOversizedFileIsReportedAsSkippedNotAsNoMatch() {
        File(workspace, "big.log").outputStream().use { o ->
            val chunk = ByteArray(1024 * 1024) { 'a'.code.toByte() }
            repeat(5) { o.write(chunk) }
            o.write("needle".toByteArray())
        }

        val out = grep("/workspace/big.log", "needle")

        assertEquals(0, out.getInt("count"))
        assertFalse("0 matches in an unread file is not a complete answer", out.getBoolean("complete"))
        assertEquals(1, out.getInt("skipped_count"))
        assertTrue(out.getJSONArray("skipped").getJSONObject(0).getString("reason").contains("larger"))
    }

    @Test
    fun theHitLimitIsReported() {
        File(workspace, "many.txt").writeText((1..80).joinToString("\n") { "needle $it" })
        val out = LinuxFileOps.let {
            runBlocking { JSONObject(it.grep(session, "/workspace/many.txt", "needle", 0, 5, TestContext.dummy()).output) }
        }
        assertEquals(5, out.getInt("count"))
        assertTrue(out.getBoolean("hit_limit_reached"))
        assertFalse(out.getBoolean("complete"))
    }
}
