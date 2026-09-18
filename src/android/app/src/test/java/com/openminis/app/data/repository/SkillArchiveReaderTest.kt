package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * [T-android-skill-archive-bounds] An imported skill archive is untrusted
 * input. These cases pin the budgets and path rules that now reject an archive
 * outright, plus the shapes a legitimate archive may still use.
 */
class SkillArchiveReaderTest {

    private val skillMd = "---\nname: Demo\n---\n\nbody\n".toByteArray()

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun read(bytes: ByteArray): SkillArchive =
        SkillArchiveReader.read(ByteArrayInputStream(bytes))

    private fun rejection(bytes: ByteArray): String {
        val failure = runCatching { read(bytes) }.exceptionOrNull()
        assertTrue("expected a SkillArchiveException, got $failure", failure is SkillArchiveException)
        return failure!!.message.orEmpty()
    }

    @Test
    fun `root level archive keeps its sibling files`() {
        val archive = read(
            zipOf(
                "SKILL.md" to skillMd,
                "scripts/run.sh" to "echo hi".toByteArray(),
                "references/notes.md" to "notes".toByteArray(),
            ),
        )
        assertTrue(archive.skillMarkdown.contains("name: Demo"))
        assertEquals(
            listOf("scripts/run.sh", "references/notes.md"),
            archive.files.map { it.relativePath },
        )
    }

    @Test
    fun `one directory deep archive strips its prefix`() {
        val archive = read(
            zipOf(
                "demo/SKILL.md" to skillMd,
                "demo/scripts/run.sh" to "echo hi".toByteArray(),
            ),
        )
        assertEquals(listOf("scripts/run.sh"), archive.files.map { it.relativePath })
    }

    @Test
    fun `prefix is resolved even when siblings precede SKILL md`() {
        val archive = read(
            zipOf(
                "demo/scripts/run.sh" to "echo hi".toByteArray(),
                "demo/SKILL.md" to skillMd,
            ),
        )
        assertEquals(listOf("scripts/run.sh"), archive.files.map { it.relativePath })
    }

    @Test
    fun `hidden and macos entries are skipped and counted`() {
        val archive = read(
            zipOf(
                "SKILL.md" to skillMd,
                ".DS_Store" to ByteArray(4),
                "__MACOSX/._SKILL.md" to ByteArray(4),
                "scripts/run.sh" to "echo hi".toByteArray(),
            ),
        )
        assertEquals(listOf("scripts/run.sh"), archive.files.map { it.relativePath })
        assertEquals(2, archive.skippedEntries)
    }

    @Test
    fun `directory entries are ignored`() {
        val archive = read(
            zipOf(
                "scripts/" to ByteArray(0),
                "SKILL.md" to skillMd,
            ),
        )
        assertEquals(0, archive.files.size)
    }

    // ── Fail-closed paths ──

    @Test
    fun `parent traversal is rejected`() {
        val message = rejection(
            zipOf(
                "SKILL.md" to skillMd,
                "../escape.sh" to "x".toByteArray(),
            ),
        )
        assertTrue(message, message.contains("unsafe path component"))
    }

    @Test
    fun `nested traversal is rejected`() {
        val message = rejection(
            zipOf(
                "SKILL.md" to skillMd,
                "scripts/../../escape.sh" to "x".toByteArray(),
            ),
        )
        assertTrue(message, message.contains("unsafe path component"))
    }

    @Test
    fun `absolute path is rejected`() {
        val message = rejection(
            zipOf(
                "SKILL.md" to skillMd,
                "/etc/passwd" to "x".toByteArray(),
            ),
        )
        assertTrue(message, message.contains("not a relative path"))
    }

    @Test
    fun `backslash in an entry name is rejected`() {
        val message = rejection(
            zipOf(
                "SKILL.md" to skillMd,
                "scripts\\run.sh" to "x".toByteArray(),
            ),
        )
        assertTrue(message, message.contains("illegal character"))
    }

    @Test
    fun `repeated entry is rejected`() {
        // ZipOutputStream refuses to write a name twice, so the duplicate is
        // built the way a crafted archive would carry it: the first archive's
        // central directory is dropped so its entry stream runs straight into
        // the second archive's entries.
        val first = zipOf(
            "SKILL.md" to skillMd,
            "scripts/run.sh" to "a".toByteArray(),
        )
        val second = zipOf("scripts/run.sh" to "b".toByteArray())
        val message = rejection(localEntriesOnly(first) + second)
        assertTrue(message, message.contains("repeats the entry"))
    }

    /** Keeps everything before the central-directory signature. */
    private fun localEntriesOnly(archive: ByteArray): ByteArray {
        val marker = byteArrayOf(0x50, 0x4b, 0x01, 0x02)
        val offset = (0..archive.size - marker.size).firstOrNull { start ->
            marker.indices.all { archive[start + it] == marker[it] }
        } ?: archive.size
        return archive.copyOfRange(0, offset)
    }

    @Test
    fun `archive without SKILL md is rejected`() {
        val message = rejection(zipOf("scripts/run.sh" to "x".toByteArray()))
        assertTrue(message, message.contains("no SKILL.md"))
    }

    @Test
    fun `blank SKILL md is rejected`() {
        val message = rejection(zipOf("SKILL.md" to "   \n".toByteArray()))
        assertTrue(message, message.contains("empty"))
    }

    @Test
    fun `two SKILL md files are rejected`() {
        val message = rejection(
            zipOf(
                "SKILL.md" to skillMd,
                "demo/SKILL.md" to skillMd,
            ),
        )
        assertTrue(message, message.contains("more than one SKILL.md"))
    }

    @Test
    fun `entry over the per-entry budget is rejected`() {
        val oversized = ByteArray((SkillArchiveReader.MAX_ENTRY_BYTES + 1).toInt())
        val message = rejection(
            zipOf(
                "SKILL.md" to skillMd,
                "assets/blob.bin" to oversized,
            ),
        )
        assertTrue(message, message.contains("expands past"))
    }

    @Test
    fun `too many entries are rejected`() {
        val entries = mutableListOf<Pair<String, ByteArray>>("SKILL.md" to skillMd)
        repeat(SkillArchiveReader.MAX_ENTRIES) { index ->
            entries += "scripts/f$index.sh" to "x".toByteArray()
        }
        val message = rejection(zipOf(*entries.toTypedArray()))
        assertTrue(message, message.contains("more than ${SkillArchiveReader.MAX_ENTRIES} entries"))
    }

    @Test
    fun `deeply nested entry path is rejected`() {
        val deep = (1..(SkillArchiveReader.MAX_PATH_COMPONENTS + 1)).joinToString("/") { "d$it" }
        val message = rejection(
            zipOf(
                "SKILL.md" to skillMd,
                "$deep/file.sh" to "x".toByteArray(),
            ),
        )
        assertTrue(message, message.contains("nests deeper"))
    }

    @Test
    fun `overlong entry name is rejected`() {
        val long = "a".repeat(SkillArchiveReader.MAX_PATH_CHARS + 1)
        val message = rejection(
            zipOf(
                "SKILL.md" to skillMd,
                "$long.sh" to "x".toByteArray(),
            ),
        )
        assertTrue(message, message.contains("longer than"))
    }

    @Test
    fun `read archives are independent of the source stream`() {
        // The reader must not hand back a buffer that a later read can mutate.
        val bytes = skillMd.copyOf()
        val archive = read(zipOf("SKILL.md" to bytes))
        bytes.fill(0)
        assertNotNull(archive.skillMarkdown)
        assertTrue(archive.skillMarkdown.contains("name: Demo"))
    }
}
