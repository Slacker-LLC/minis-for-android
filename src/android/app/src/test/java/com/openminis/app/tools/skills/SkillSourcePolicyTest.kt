package com.openminis.app.tools.skills

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-skill-tools] Ported from Eta `agent/model/AgentSkillToolCatalog.kt` and its
 * discovery/install path (Mangi-11/Eta @ c15de97). Parsing and refusals are tested
 * without network: the fetch layer only ever sees what this policy produced.
 */
class SkillSourcePolicyTest {

    @Test
    fun `owner repo shorthand and url forms parse to the same ref`() {
        val expected = SkillSourcePolicy.RepoRef("openai", "skills", null, null)

        assertEquals(expected, SkillSourcePolicy.parseRepository("openai/skills"))
        assertEquals(expected, SkillSourcePolicy.parseRepository("https://github.com/openai/skills"))
        assertEquals(expected, SkillSourcePolicy.parseRepository("github.com/openai/skills/"))
        assertEquals(expected, SkillSourcePolicy.parseRepository("  openai/skills  "))
    }

    @Test
    fun `a git suffix is not part of the repository name`() {
        assertEquals(
            SkillSourcePolicy.RepoRef("openai", "skills", null, null),
            SkillSourcePolicy.parseRepository("openai/skills.git"),
        )
    }

    @Test
    fun `tree blob and raw forms carry the ref and the directory`() {
        val expected = SkillSourcePolicy.RepoRef("openai", "skills", "main", "skills/pdf")

        assertEquals(expected, SkillSourcePolicy.parseRepository("https://github.com/openai/skills/tree/main/skills/pdf"))
        assertEquals(
            expected,
            SkillSourcePolicy.parseRepository("https://github.com/openai/skills/blob/main/skills/pdf/SKILL.md"),
        )
        assertEquals(
            expected,
            SkillSourcePolicy.parseRepository("https://raw.githubusercontent.com/openai/skills/main/skills/pdf/SKILL.md"),
        )
        assertEquals(expected, SkillSourcePolicy.parseRepository("openai/skills/tree/main/skills/pdf"))
    }

    @Test
    fun `a bare tree marker is not a path`() {
        val parsed = SkillSourcePolicy.parseRepository("openai/skills/tree")!!

        assertNull(parsed.ref)
        assertNull(parsed.path)
    }

    @Test
    fun `other hosts and unusable input are refused`() {
        assertNull(SkillSourcePolicy.parseRepository(""))
        assertNull(SkillSourcePolicy.parseRepository("openai"))
        assertNull(SkillSourcePolicy.parseRepository("gitlab.com/openai/skills"))
        assertNull(SkillSourcePolicy.parseRepository("https://example.com/openai/skills"))
        assertNull(SkillSourcePolicy.parseRepository("https://raw.githubusercontent.com/openai"))
    }

    @Test
    fun `narrowing paths stay relative and safe`() {
        assertEquals("skills/pdf", SkillSourcePolicy.safeRelativeDirectory("skills/pdf"))
        assertEquals("skills/pdf", SkillSourcePolicy.safeRelativeDirectory("./skills/pdf"))
        assertEquals("skills/pdf", SkillSourcePolicy.safeRelativeDirectory("skills/pdf/"))

        assertNull(SkillSourcePolicy.safeRelativeDirectory("/etc/passwd"))
        assertNull(SkillSourcePolicy.safeRelativeDirectory("../other"))
        assertNull(SkillSourcePolicy.safeRelativeDirectory("skills/../../escape"))
        assertNull(SkillSourcePolicy.safeRelativeDirectory("skills\\pdf"))
        assertNull(SkillSourcePolicy.safeRelativeDirectory("bad\u0000path"))
        assertNull(SkillSourcePolicy.safeRelativeDirectory(""))
        assertNull(SkillSourcePolicy.safeRelativeDirectory("   "))
        assertNull(SkillSourcePolicy.safeRelativeDirectory("x".repeat(1_001)))
    }

    @Test
    fun `only directories that hold a SKILL md are candidates`() {
        val tree = treeJson(
            "README.md" to "blob",
            "skills/pdf/SKILL.md" to "blob",
            "skills/pdf/references/guide.md" to "blob",
            "skills/pdf" to "tree",
            "skills/other/skill.md" to "blob",
            "docs/guide.md" to "blob",
        )

        val candidates = SkillSourcePolicy.skillDirectories(tree, null)

        assertEquals(listOf("skills/other", "skills/pdf"), candidates.paths)
        assertFalse(candidates.truncated)
    }

    @Test
    fun `a repository root SKILL md is not a skill directory`() {
        val tree = treeJson("SKILL.md" to "blob")

        assertTrue(SkillSourcePolicy.skillDirectories(tree, null).paths.isEmpty())
    }

    @Test
    fun `narrowing keeps the directory and its children only`() {
        val tree = treeJson(
            "skills/pdf/SKILL.md" to "blob",
            "skills/pdf-forms/SKILL.md" to "blob",
            "skills/pdf/advanced/SKILL.md" to "blob",
        )

        assertEquals(
            listOf("skills/pdf", "skills/pdf/advanced"),
            SkillSourcePolicy.skillDirectories(tree, "skills/pdf").paths,
        )
        assertTrue(SkillSourcePolicy.skillDirectories(tree, "skills/other").paths.isEmpty())
    }

    @Test
    fun `an unusable tree body yields no candidates instead of throwing`() {
        assertTrue(SkillSourcePolicy.skillDirectories("not json", null).paths.isEmpty())
        assertTrue(SkillSourcePolicy.skillDirectories("{\"message\":\"Not Found\"}", null).paths.isEmpty())
    }

    @Test
    fun `the candidate list is capped and says so`() {
        val entries = (0 until SkillSourcePolicy.MAX_CANDIDATES + 5).map { "skills/s$it/SKILL.md" to "blob" }

        val candidates = SkillSourcePolicy.skillDirectories(treeJson(*entries.toTypedArray()), null)

        assertEquals(SkillSourcePolicy.MAX_CANDIDATES, candidates.paths.size)
        assertTrue(candidates.truncated)
    }

    @Test
    fun `fetch urls are built from the pinned commit`() {
        assertEquals(
            "https://raw.githubusercontent.com/openai/skills/abc123/SKILL.md",
            SkillSourcePolicy.rawSkillMdUrl("openai", "skills", "abc123", null),
        )
        assertEquals(
            "https://raw.githubusercontent.com/openai/skills/abc123/skills/pdf/SKILL.md",
            SkillSourcePolicy.rawSkillMdUrl("openai", "skills", "abc123", "skills/pdf"),
        )
        assertEquals(
            "https://api.github.com/repos/openai/skills/commits/HEAD",
            SkillSourcePolicy.commitApiUrl("openai", "skills", "HEAD"),
        )
        assertEquals(
            "https://api.github.com/repos/openai/skills/git/trees/abc123?recursive=1",
            SkillSourcePolicy.treeApiUrl("openai", "skills", "abc123"),
        )
        assertEquals(
            "https://github.com/openai/skills/tree/abc123/skills/pdf",
            SkillSourcePolicy.htmlSkillUrl("openai", "skills", "abc123", "skills/pdf"),
        )
    }

    private fun treeJson(vararg entries: Pair<String, String>): String = JSONObject()
        .put("sha", "abc123")
        .put(
            "tree",
            JSONArray().also { array ->
                entries.forEach { (path, type) ->
                    array.put(JSONObject().put("path", path).put("type", type))
                }
            },
        )
        .toString()
}
