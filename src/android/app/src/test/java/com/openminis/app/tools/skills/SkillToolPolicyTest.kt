package com.openminis.app.tools.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-skill-tools] Ported from Eta `agent/model/AgentSkillToolCatalog.kt`
 * (Mangi-11/Eta @ c15de97). Bounds, resolution and the refusals are the contract the
 * tool schema advertises, so each one is asserted here.
 */
class SkillToolPolicyTest {

    private val git = SkillToolPolicy.SkillEntry(
        id = "git-helper",
        name = "Git Helper",
        description = "Commit, rebase and resolve conflicts",
        enabled = true,
    )
    private val pdf = SkillToolPolicy.SkillEntry(
        id = "pdf-forms",
        name = "PDF Forms",
        description = "Fill AcroForms",
        enabled = false,
    )
    private val entries = listOf(git, pdf)

    @Test
    fun `list options default and clamp the limit`() {
        assertEquals(SkillToolPolicy.DEFAULT_LIST_LIMIT, SkillToolPolicy.listOptions(null, null).limit)
        assertEquals(1, SkillToolPolicy.listOptions(null, 0).limit)
        assertEquals(SkillToolPolicy.MAX_LIST_LIMIT, SkillToolPolicy.listOptions(null, 10_000).limit)
        assertEquals(7, SkillToolPolicy.listOptions(null, 7).limit)
        assertNull(SkillToolPolicy.listOptions("   ", null).query)
        assertEquals("git", SkillToolPolicy.listOptions("  GIT ", null).query)
    }

    @Test
    fun `max chars default and clamp`() {
        assertEquals(SkillToolPolicy.DEFAULT_MAX_CHARS, SkillToolPolicy.clampMaxChars(null))
        assertEquals(SkillToolPolicy.MIN_MAX_CHARS, SkillToolPolicy.clampMaxChars(10))
        assertEquals(SkillToolPolicy.MAX_MAX_CHARS, SkillToolPolicy.clampMaxChars(1_000_000))
        assertEquals(4_000, SkillToolPolicy.clampMaxChars(4_000))
    }

    @Test
    fun `query matches id name or description and ignores case`() {
        assertTrue(SkillToolPolicy.matches(git, "git"))
        assertTrue(SkillToolPolicy.matches(git, "helper"))
        assertTrue(SkillToolPolicy.matches(git, "rebase"))
        assertFalse(SkillToolPolicy.matches(git, "calendar"))
        assertTrue("a null query matches everything", SkillToolPolicy.matches(git, null))
    }

    @Test
    fun `listing reports counts and the truncation footer`() {
        val options = SkillToolPolicy.listOptions(null, 1)

        val text = SkillToolPolicy.formatList(listOf(git), matchedTotal = 2, options = options)

        assertTrue(text.startsWith("1 of 2 skill(s):"))
        assertTrue(text.contains("- id: git-helper | name: Git Helper | enabled: true"))
        assertTrue(text.contains("path: /var/minis/skills/git-helper/SKILL.md"))
        assertTrue(text.contains("1 more match; raise limit"))
    }

    @Test
    fun `listing without matches says so with and without a query`() {
        assertEquals(
            "No skills are installed.",
            SkillToolPolicy.formatList(emptyList(), 0, SkillToolPolicy.listOptions(null, null)),
        )
        assertEquals(
            "No installed skill matches 'git'.",
            SkillToolPolicy.formatList(emptyList(), 0, SkillToolPolicy.listOptions("git", null)),
        )
    }

    @Test
    fun `long descriptions are shortened in a listing`() {
        val long = git.copy(description = "x".repeat(500))

        val text = SkillToolPolicy.formatList(listOf(long), 1, SkillToolPolicy.listOptions(null, null))

        assertTrue(text.contains("…"))
        assertTrue("listing stays bounded", text.length < 500)
    }

    @Test
    fun `skill ids resolve by id name and path`() {
        assertEquals("git-helper", SkillToolPolicy.resolveSkillId("git-helper", entries))
        assertEquals("git-helper", SkillToolPolicy.resolveSkillId("GIT-HELPER", entries))
        assertEquals("git-helper", SkillToolPolicy.resolveSkillId("Git Helper", entries))
        assertEquals(
            "git-helper",
            SkillToolPolicy.resolveSkillId("/var/minis/skills/git-helper/SKILL.md", entries),
        )
        assertEquals("git-helper", SkillToolPolicy.resolveSkillId("git-helper/SKILL.md", entries))
        assertEquals("pdf-forms", SkillToolPolicy.resolveSkillId(" pdf-forms ", entries))
        assertNull(SkillToolPolicy.resolveSkillId("nope", entries))
        assertNull(SkillToolPolicy.resolveSkillId("   ", entries))
    }

    @Test
    fun `truncation is explicit and keeps the original length visible`() {
        val short = SkillToolPolicy.truncate("hello", 10)
        assertFalse(short.truncated)
        assertEquals("hello", short.text)

        val exact = SkillToolPolicy.truncate("hello", 5)
        assertFalse("a body exactly at the cap is not truncated", exact.truncated)

        val long = SkillToolPolicy.truncate("abcdefghij", 4)
        assertTrue(long.truncated)
        assertEquals(10, long.originalChars)
        assertTrue(long.text.startsWith("abcd"))
        assertTrue(long.text.contains("[truncated: 4 of 10 characters shown]"))
    }

    @Test
    fun `resource paths stay inside the skill`() {
        val ok = SkillToolPolicy.resourcePath("references/guide.md") as SkillToolPolicy.ResourcePath.Ok
        assertEquals("references/guide.md", ok.path)

        fun refused(raw: String): String {
            val result = SkillToolPolicy.resourcePath(raw)
            assertTrue("expected a refusal for '$raw', got $result", result is SkillToolPolicy.ResourcePath.Refused)
            return (result as SkillToolPolicy.ResourcePath.Refused).reason
        }

        assertTrue(refused("").contains("required"))
        assertTrue(refused("/etc/passwd").contains("relative"))
        assertTrue(refused("../other-skill/SKILL.md").contains(".."))
        assertTrue(refused("references/../../escape.md").contains(".."))
        assertTrue(refused("references\\guide.md").contains("forward slashes"))
        assertTrue(refused("bad\u0000name").contains("control character"))
        assertTrue(refused("x".repeat(1_001)).contains("1000 characters"))
    }

    @Test
    fun `the skill path helper matches the prompt fragment`() {
        assertEquals("/var/minis/skills/pdf-forms/SKILL.md", SkillToolPolicy.skillMdPath("pdf-forms"))
    }
}
