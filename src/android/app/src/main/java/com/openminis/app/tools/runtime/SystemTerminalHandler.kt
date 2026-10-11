package com.openminis.app.tools.runtime

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.runtime.terminal.AgentKeyEncoder
import com.openminis.app.runtime.terminal.AgentTerminals
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.ui.terminal.emulator.ProgramState
import com.openminis.app.ui.terminal.emulator.StatusRecord
import com.openminis.app.ui.terminal.emulator.sanitizeStatusText
import org.json.JSONObject

/**
 * The agent's real terminals: open one, type into it, press keys, read the screen. For an interactive program (a
 * coding agent such as pi / Claude Code / Codex, an editor, a REPL) that needs a terminal; a plain command stays on
 * `shell_execute`, which is cheaper and returns an exit code.
 */
class SystemTerminalHandler(
    /** Where the terminals live; tests pass their own. */
    private val registry: (Context) -> AgentTerminals = { AgentTerminals.shared(it) },
) : ToolHandler {
    override val definition: AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Drive a real terminal (a pty with a screen) for programs that need one: coding agents like pi, " +
            "claude or codex, editors, REPLs. action=open starts a login shell in this chat's workspace (command= runs " +
            "a line at once, e.g. \"pi\"); send types text and/or presses keys, waits, and returns the screen; read " +
            "returns the screen again (\"[screen unchanged]\" when nothing moved); list shows your terminals; close ends one; " +
            "click / scroll operate a full-screen program that takes the mouse; resize changes the size. " +
            "Text: a newline is Enter, but when the program takes bracketed paste a multi-line text is pasted as one block " +
            "and only its trailing newline presses Enter. Keys: Enter Escape Tab Backspace Up Down Left Right Home End " +
            "PageUp PageDown F1-F12 Ctrl-C Ctrl-D Alt-b ... (space separated). It waits until the screen is quiet, " +
            "wait_for matches, the program reports done/blocked/error (OSC 7501), it exits, or wait_ms runs out. " +
            "A command that stays silent for a while (a build, a download) is still running: send it with until_prompt=true " +
            "to wait for the shell prompt to come back instead of for a quiet screen. " +
            "Every reply starts with the cursor position and the modes the program set; in a full-screen program the " +
            "highlighted text (the selected menu item) is marked ⟦like this⟧. find=<regex> searches the whole kept history. " +
            "A program that reports its status shows as a [status: ...] line. For a one-shot task prefer the program's " +
            "non-interactive mode through shell_execute (pi -p, claude -p, codex exec); use this when it must stay open. " +
            "At most 3 terminals per chat.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this call does, shown to the user. Use the same language as the user."),
            "action" to AgentToolParam("string", "open | send | read | list | close | click | scroll | resize", enumValues = listOf("open", "send", "read", "list", "close", "click", "scroll", "resize")),
            "term_id" to AgentToolParam("string", "The terminal's id (send / read / close), as returned by open."),
            "command" to AgentToolParam("string", "open: a command line to run right after the shell starts, for example pi."),
            "text" to AgentToolParam("string", "send: literal text to type."),
            "keys" to AgentToolParam("string", "send: key names pressed after the text, space separated, for example \"Escape\" or \"Ctrl-C\" or \"Down Down Enter\"."),
            "wait_ms" to AgentToolParam("integer", "Longest wait for the screen to settle (default 2000, open 3000, at most 30000)."),
            "wait_for" to AgentToolParam("string", "A regular expression to wait for on the screen instead of waiting for quiet."),
            "scrollback" to AgentToolParam("integer", "read: this many lines of history above the screen (default 0, at most 200)."),
            "cols" to AgentToolParam("integer", "open / resize: width in characters (default 100, 40-200)."),
            "rows" to AgentToolParam("integer", "open / resize: height in lines (default 32, 10-60)."),
            "until_prompt" to AgentToolParam("boolean", "send / open: wait until the shell prompt is back (the command finished) rather than for a quiet screen. Use for builds, installs, downloads."),
            "find" to AgentToolParam("string", "read / send: a regular expression to search the whole kept history and screen for; matching lines come back with their line numbers (at most 40)."),
            "marks" to AgentToolParam("boolean", "Mark highlighted text (reverse video or a coloured background) with ⟦ ⟧. Default: on in a full-screen program, off in a shell."),
            "row" to AgentToolParam("integer", "click: the line on the screen, 1 is the top, as the screen text is numbered."),
            "col" to AgentToolParam("integer", "click: the column, 1 is the left."),
            "clicks" to AgentToolParam("integer", "click: 1 (default) or 2 for a double click."),
            "direction" to AgentToolParam("string", "scroll: down (see lower content) or up.", enumValues = listOf("down", "up")),
            "amount" to AgentToolParam("integer", "scroll: how many lines (default 5, at most 12)."),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "term_id", "command", "text", "keys", "until_prompt", "wait_ms", "wait_for", "find", "scrollback", "marks", "row", "col", "clicks", "direction", "amount", "cols", "rows"),
        timeoutMs = 60_000L,
    )

    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String): ToolExecutionResult {
        val args = runCatching { JSONObject(argsJson) }.getOrNull()
            ?: return ToolExecutionResult("terminal: invalid arguments JSON", false)
        val title = args.optString("tool_title", "terminal")
        val terminals = registry(context)
        fun fail(message: String) = ToolExecutionResult("terminal: $message", false, toolTitle = title)
        fun ok(text: String) = ToolExecutionResult(text, true, toolTitle = title)
        fun waitMs(default: Long) = args.optLong("wait_ms", default).coerceIn(0L, MAX_WAIT_MS)
        val waitFor = args.optString("wait_for").takeIf { it.isNotBlank() }?.let {
            runCatching { Regex(it) }.getOrNull() ?: return fail("wait_for is not a valid regular expression")
        }

        when (val action = args.optString("action")) {
            "open" -> {
                val terminal = try {
                    terminals.open(
                        sessionId = sessionId,
                        cols = args.optInt("cols", AgentTerminals.DEFAULT_COLS),
                        rows = args.optInt("rows", AgentTerminals.DEFAULT_ROWS),
                    )
                } catch (limit: AgentTerminals.LimitExceeded) {
                    return fail(limit.message ?: "too many terminals")
                }
                // Wait for the first prompt before typing, or the line lands in a shell that is not reading yet.
                terminals.await(terminal, maxMs = 8_000L, quietMs = 700L)
                val command = args.optString("command").trim()
                val baseline = terminals.baseline(terminal)
                if (command.isNotEmpty()) terminals.send(terminal, (command + "\r").toByteArray(Charsets.UTF_8))
                val end = terminals.await(
                    terminal, waitMs(3_000L), waitFor = waitFor, statusBaseline = baseline,
                    untilPrompt = command.isNotEmpty() && args.optBoolean("until_prompt", false),
                )
                return ok(render(terminals, terminal, end, scrollback = 0, allowUnchanged = false, marks = marksFor(terminals, terminal, args), find = null))
            }
            "list" -> {
                val own = terminals.list(sessionId)
                if (own.isEmpty()) return ok("no terminals open in this chat")
                return ok(own.joinToString("\n") { t ->
                    val state = if (t.exited) "exited" else "running"
                    "${t.id} · $state · ${t.cols}x${t.rows}" + (headline(terminals.status(t))?.let { " · $it" } ?: "")
                })
            }
            "send", "read", "close", "click", "scroll", "resize" -> {
                val id = args.optString("term_id").trim()
                val terminal = terminals.get(id)?.takeIf { it.sessionId == sessionId }
                    ?: return fail("no terminal '$id' in this chat (terminal action=list shows yours)")
                if (action == "close") {
                    terminals.close(id)
                    return ok("closed $id")
                }
                if (action == "resize") {
                    if (terminal.exited) return fail("$id has exited; open a new terminal")
                    terminals.resize(terminal, args.optInt("cols", terminal.cols), args.optInt("rows", terminal.rows))
                    // The program redraws for the new size; give it a moment.
                    val end = terminals.await(terminal, waitMs(1_500L), statusBaseline = terminals.baseline(terminal))
                    return ok(render(terminals, terminal, end, 0, allowUnchanged = false, marks = marksFor(terminals, terminal, args), find = null))
                }
                val baseline = terminals.baseline(terminal)
                if (action == "click") {
                    if (terminal.exited) return fail("$id has exited; open a new terminal")
                    val row = args.optInt("row", 0)
                    val col = args.optInt("col", 0)
                    if (row < 1 || col < 1) return fail("click needs row and col (1 is the top left of the screen)")
                    if (row > terminal.rows || col > terminal.cols) return fail("row/col outside the ${terminal.cols}x${terminal.rows} screen")
                    if (!terminals.click(terminal, row, col, args.optInt("clicks", 1))) {
                        return fail("this program has not asked for the mouse; use keys instead")
                    }
                }
                if (action == "scroll") {
                    if (terminal.exited) return fail("$id has exited; open a new terminal")
                    val amount = args.optInt("amount", 5).coerceIn(1, 12)
                    val down = args.optString("direction", "down") != "up"
                    if (!terminals.scroll(terminal, if (down) amount else -amount)) {
                        return fail("nothing to scroll: a plain shell keeps its history; read it with scrollback=")
                    }
                }
                if (action == "send") {
                    val text = args.optString("text")
                    val keys = args.optString("keys")
                    if (text.isEmpty() && keys.isBlank()) return fail("send needs text and/or keys")
                    if (terminal.exited) return fail("$id has exited; open a new terminal")
                    val (appCursor, paste) = terminals.application(terminal)
                    val encodedKeys = AgentKeyEncoder.encodeAll(keys, appCursor)
                    if (encodedKeys.unknown.isNotEmpty()) {
                        return fail("unknown key name(s): ${encodedKeys.unknown.joinToString(" ")}; nothing was sent")
                    }
                    if (text.isNotEmpty()) terminals.send(terminal, AgentKeyEncoder.encodeText(text, paste))
                    if (encodedKeys.bytes.isNotEmpty()) terminals.send(terminal, encodedKeys.bytes)
                }
                val end = terminals.await(
                    terminal, waitMs(2_000L), waitFor = waitFor, statusBaseline = baseline,
                    untilPrompt = action == "send" && args.optBoolean("until_prompt", false),
                )
                val scrollback = args.optInt("scrollback", 0).coerceIn(0, MAX_SCROLLBACK)
                val find = args.optString("find").takeIf { it.isNotBlank() }?.let {
                    runCatching { Regex(it) }.getOrNull() ?: return fail("find is not a valid regular expression")
                }
                return ok(render(terminals, terminal, end, scrollback, allowUnchanged = action == "read" && find == null, marks = marksFor(terminals, terminal, args), find = find))
            }
            else -> return fail("unknown action '$action' (open, send, read, list, close, click, scroll, resize)")
        }
    }

    private fun render(
        terminals: AgentTerminals,
        terminal: AgentTerminals.Terminal,
        end: AgentTerminals.End,
        scrollback: Int,
        allowUnchanged: Boolean,
        marks: Boolean,
        find: Regex?,
    ): String {
        // Privacy Mode masks the user's environment-variable values on anything the model reads.
        val screen = com.openminis.app.data.EnvVarRedactor.redactIfEnabled(terminals.screen(terminal, scrollback, marks)).first
            .let { if (it.length > MAX_SCREEN_CHARS) it.takeLast(MAX_SCREEN_CHARS) else it }
        val hash = screen.hashCode()
        val unchanged = allowUnchanged && terminal.lastScreenHashForTool == hash
        terminal.lastScreenHashForTool = hash
        val header = "[terminal ${terminal.id} · ${if (terminal.exited) "exited" else "running"} · ${terminal.cols}x${terminal.rows}]" +
            "\n[${terminals.cursorAndModes(terminal)}]"
        val status = headline(terminals.status(terminal))?.let { "\n[status: $it]" }.orEmpty()
        return buildString {
            append(header).append('\n')
            append(if (unchanged) "[screen unchanged]" else screen.ifEmpty { "(blank screen)" })
            append(status)
            if (find != null) append(findSection(terminals, terminal, find))
            append("\n[waited: ").append(describe(end)).append(']')
        }
    }

    /** Highlights are marked by default only where they carry meaning: in a full-screen program. */
    private fun marksFor(terminals: AgentTerminals, terminal: AgentTerminals.Terminal, args: JSONObject): Boolean =
        if (args.has("marks")) args.optBoolean("marks") else terminals.inFullScreen(terminal)

    private fun findSection(terminals: AgentTerminals, terminal: AgentTerminals.Terminal, pattern: Regex): String {
        val (lines, total) = terminals.find(terminal, pattern, MAX_FOUND)
        if (lines.isEmpty()) return "\n[find /${pattern.pattern}/: no match in $total lines]"
        val body = lines.joinToString("\n") { "L${it.number}: ${com.openminis.app.data.EnvVarRedactor.redactIfEnabled(it.text.take(MAX_FOUND_CHARS)).first}" }
        return "\n[find /${pattern.pattern}/: ${lines.size} match(es) in $total lines, L1 is the oldest kept]\n$body"
    }

    private fun describe(end: AgentTerminals.End) = when (end) {
        AgentTerminals.End.QUIET -> "screen quiet"
        AgentTerminals.End.MATCH -> "wait_for matched"
        AgentTerminals.End.STATUS -> "program reported done / blocked / error"
        AgentTerminals.End.PROMPT -> "shell prompt is back (the command finished)"
        AgentTerminals.End.EXITED -> "program exited"
        AgentTerminals.End.TIMEOUT -> "wait_ms ran out; the program may still be working"
    }

    companion object {
        const val NAME = "terminal"
        private const val MAX_WAIT_MS = 30_000L
        private const val MAX_SCROLLBACK = 200
        private const val MAX_SCREEN_CHARS = 8_000
        private const val MAX_FOUND = 40
        private const val MAX_FOUND_CHARS = 300

        private val URGENCY = listOf(ProgramState.BLOCKED, ProgramState.ERROR, ProgramState.WORKING, ProgramState.DONE, ProgramState.IDLE)

        /** One line for the most urgent OSC 7501 record: "blocked (permission) · pi · Allow bash?". */
        internal fun headline(records: List<StatusRecord>): String? {
            val record = records.reversed().minByOrNull { URGENCY.indexOf(it.state) } ?: return null
            val parts = ArrayList<String>(3)
            parts.add(record.state.name.lowercase() + (record.kind?.let { " (${it.name.lowercase()})" } ?: ""))
            record.app?.let { parts.add(it) }
            record.msg?.let { sanitizeStatusText(it).take(200).takeIf { text -> text.isNotBlank() }?.let(parts::add) }
            return parts.joinToString(" · ")
        }
    }
}
