package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The model-facing text of the resident tools: how many bytes it costs every request, and the parts of the
 * ASD-STE100 style that a machine can check (sentence length, paragraph length, banned abbreviations).
 * Nothing here proves full STE compliance: voice, tense, vocabulary and one-idea-per-sentence are checked
 * by reading, not by this test.
 *
 * The safety test pins the sentences that refuse or limit something. A rewrite that shortens the text must
 * not drop them.
 */
class ToolDefinitionTextTest {
    private val noMcp: (AgentToolDefinition) -> Boolean = { false }

    /**
     * What every request carries: the resident part of the real pool. The registry's web tools are added by
     * hand because this test does not run MinisApp.onCreate; everything else comes from makeAgentTools.
     */
    private fun resident(): List<AgentToolDefinition> {
        val pool = AgentTools.makeAgentTools(
            subAgentRosterNames = listOf("General Sub Agent"), subAgentModelHandles = listOf("a", "b"),
        ) + AndroidWebSearchHandler().definition + AndroidUrlFetchHandler().definition
        return ToolDeferral.split(pool.distinctBy { it.name }, noMcp).resident
    }

    private fun byName(name: String) = resident().first { it.name == name }

    private fun bytes(def: AgentToolDefinition) = def.toOpenAIJson().toString().length

    // ── Bytes ───────────────────────────────────────────────────────────────

    @Test
    fun `the resident definitions stay inside their byte budget`() {
        val tools = resident()
        assertEquals("every resident name must be in the pool", ToolDeferral.RESIDENT_NAMES.size, tools.size)
        val total = tools.sumOf(::bytes)
        // Was 34,386 B before the rewrite (docs: PR description). The cap leaves room for small edits only.
        assertTrue("resident definitions are $total B", total <= 31_000)
        val biggest = tools.maxBy(::bytes)
        assertTrue("${biggest.name} is ${bytes(biggest)} B", bytes(biggest) <= 7_000)
    }

    @Test
    fun `the five goal tools do not share one description`() {
        val goals = listOf("get_goal", "create_goal", "update_goal", "verify_outcome", "review_restored_task").map(::byName)
        assertEquals(5, goals.map { it.description }.distinct().size)
        // A rule that applies to all of them is written once, in the parameter schema or in one tool.
        assertTrue(goals.sumOf { it.description.length } <= 2_000)
        assertTrue(byName("get_goal").parameters.keys == setOf("tool_title"))
    }

    // ── Style that a machine can check ─────────────────────────────────────

    @Test
    fun `every tool_title parameter has the one shared wording`() {
        val withTitle = resident().filter { "tool_title" in it.parameters }
        assertTrue(withTitle.size >= 17)
        for (def in withTitle) assertEquals(def.name, ToolText.TOOL_TITLE, def.parameters["tool_title"])
        assertTrue(ToolText.TOOL_TITLE.description.contains("language of the user"))
    }

    @Test
    fun `sentences are short and paragraphs hold at most six sentences`() {
        val problems = mutableListOf<String>()
        for (def in resident()) {
            for ((where, text) in texts(def)) {
                for (paragraph in text.split("\n\n")) {
                    val sentences = sentences(paragraph)
                    if (sentences.size > 6) problems += "${def.name} $where: ${sentences.size} sentences in one paragraph"
                    for (sentence in sentences) {
                        val words = wordCount(sentence)
                        val limit = if (isInstruction(sentence)) 20 else 25
                        if (words > limit) problems += "${def.name} $where: $words words (limit $limit): $sentence"
                    }
                }
            }
        }
        assertTrue("STE sentence rules broken:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `no unexplained abbreviations or banned filler words`() {
        val banned = Regex("""\b(e\.g\.|i\.e\.|etc\.?|via|utilize|invocation)\b""", RegexOption.IGNORE_CASE)
        val problems = resident().flatMap { def ->
            texts(def).mapNotNull { (where, text) -> banned.find(text)?.let { "${def.name} $where: '${it.value}'" } }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun `the sentence checker itself counts what it should`() {
        assertEquals(listOf("Read a file.", "Do not use it."), sentences("Read a file. Do not use it."))
        assertEquals(1, sentences("Pass N as offset. ".trim()).size)
        assertEquals(1, sentences("Run: . /var/x/env.sh && command.").size)
        assertEquals(2, sentences("Set next_offset=N. Pass it back.").size)
        assertEquals(2, sentences("Set it. next_offset is a number.").size)
        assertEquals(5, wordCount("Use job_output, job_list, and job_kill."))
        assertTrue(isInstruction("Use the tool."))
        assertTrue(isInstruction("If the job runs, set eof=true."))
        assertFalse(isInstruction("The tool returns a job_id."))
    }

    // ── Refusals and limits survive the rewrite ────────────────────────────

    private fun text(def: AgentToolDefinition) = texts(def).joinToString("\n") { it.second }

    private fun assertSays(tool: String, vararg phrases: String) {
        val all = text(byName(tool))
        for (phrase in phrases) assertTrue("$tool must still say: $phrase", all.contains(phrase))
    }

    @Test
    fun `safety limits stay in the resident text`() {
        assertSays("shell_execute", "as the Android app user", "no Linux capabilities", "Root only starts the chroot", "from 0 to 600")
        assertSays("memory_write", "Do not save passwords, API keys, tokens, or secrets", "explicitly confirms", "GLOBAL.md is read-only", "Neither type is a current instruction")
        assertSays("browser_use", "Do not use this tool for minis:// action URLs", "It does not return raw cookie values", "up to 3 tabs")
        assertSays("android.web.fetch", "blocks private and local destinations", "HTTPS")
        assertSays("file_read", "A binary file is rejected", "next_offset=N")
        assertSays("file_edit", "Always read the relevant region with file_read first", "rejects overlapping edits")
        assertSays("ask_user_question", "Do not use it for a rhetorical question", "one question at a time")
        assertSays("todo_write", "Send the complete list every time", "no partial updates")
        assertSays("find_tools", "A listing with no query loads nothing", "maximum of 6 tools", "android_capabilities")
        assertSays("job_kill", "cancellation-requested", "already-finished")
        assertSays("job_input", "end a line with \\n", "eof=true")
        assertSays("create_goal", "must not be blank", "fails if the session already has a goal")
        assertSays("update_goal", "Do not do both in one call", "A reply alone never completes the goal", "also send evidence")
        assertSays("verify_outcome", "Do not send status or evidence", "never runs the action again", "stays pending")
        assertSays("review_restored_task", "The task stays paused", "actual user")
    }

    @Test
    fun `the parameters of the resident tools did not change`() {
        // Names and order are the contract with the model and with the validator; only the wording moved.
        val shape = resident().associate { it.name to (it.parameters.keys.toList() to it.required) }
        assertEquals(listOf("tool_title", "command", "timeout", "delay", "background"), shape.getValue("shell_execute").first)
        assertEquals(listOf("tool_title", "command"), shape.getValue("shell_execute").second)
        assertEquals(setOf("tool_title", "action", "url", "selector", "text", "coordinate_x", "coordinate_y", "direction", "amount", "script", "user_agent", "max_depth", "scroll_count", "item_selector", "tab_id", "keywords", "fuzzy", "cookies", "timeout", "timeout_ms", "submit", "read_image", "viewport_width", "viewport_height", "reset"), shape.getValue("browser_use").first.toSet())
        assertEquals(listOf("tool_title", "action"), shape.getValue("browser_use").second)
        assertEquals(listOf("tool_title", "path", "edits"), shape.getValue("file_edit").second)
        assertNotNull(byName("browser_use").parameters["action"]?.enumValues)
    }

    // ── The checker ────────────────────────────────────────────────────────

    /** Every piece of model-facing prose in [def]: its description and all parameter descriptions, nested ones too. */
    private fun texts(def: AgentToolDefinition): List<Pair<String, String>> {
        val out = mutableListOf("description" to def.description)
        fun walk(path: String, param: AgentToolParam) {
            out += path to param.description
            param.items?.let { walk("$path[]", it) }
            param.properties?.forEach { (key, value) -> walk("$path.$key", value) }
        }
        def.parameters.forEach { (key, value) -> walk(key, value) }
        return out
    }

    /** A sentence ends at . ! or ? followed by a space and a letter, so `a.b`, `. /path` and `1.5` stay whole. */
    private fun sentences(paragraph: String): List<String> =
        paragraph.trim().split(Regex("(?<=[.!?])\\s+(?=[A-Za-z_])")).map { it.trim() }.filter { it.isNotEmpty() }

    /** Words are runs of non-space characters that hold a letter or a digit; a lone dash or bracket is not a word. */
    private fun wordCount(sentence: String): Int = sentence.split(Regex("\\s+")).count { word -> word.any { it.isLetterOrDigit() } }

    private val imperatives = setOf(
        "use", "set", "send", "pass", "write", "call", "do", "read", "keep", "check", "ask", "omit", "leave", "run",
        "start", "always", "give", "save", "search", "find", "get", "create", "merge", "attach", "add", "stop",
    )
    private val leadIns = setOf("to", "if", "when", "while", "after", "before", "for", "with", "without")

    /** An instruction starts with an imperative verb, or with a lead-in clause ("If X, ...") followed by one. */
    private fun isInstruction(sentence: String): Boolean {
        val first = sentence.substringBefore(' ').lowercase().trim(',', ':')
        if (first in imperatives) return true
        if (first !in leadIns || ',' !in sentence) return false
        val rest = sentence.substringAfter(',').trim().substringBefore(' ').lowercase().trim(',', ':')
        return rest in imperatives
    }
}
