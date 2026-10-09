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
            "returns the screen again (\"[screen unchanged]\" when nothing moved); list shows your terminals; close ends one. " +
            "Text: a newline is Enter, but when the program takes bracketed paste a multi-line text is pasted as one block " +
            "and only its trailing newline presses Enter. Keys: Enter Escape Tab Backspace Up Down Left Right Home End " +
            "PageUp PageDown F1-F12 Ctrl-C Ctrl-D Alt-b ... (space separated). It waits until the screen is quiet, " +
            "wait_for matches, the program reports done/blocked/error (OSC 7501), it exits, or wait_ms runs out. " +
            "A program that reports its status shows as a [status: ...] line. For a one-shot task prefer the program's " +
            "non-interactive mode through shell_execute (pi -p, claude -p, codex exec); use this when it must stay open. " +
            "At most 3 terminals per chat.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary of what this call does, shown to the user. Use the same language as the user."),
            "action" to AgentToolParam("string", "open | send | read | list | close", enumValues = listOf("open", "send", "read", "list", "close")),
            "term_id" to AgentToolParam("string", "The terminal's id (send / read / close), as returned by open."),
            "command" to AgentToolParam("string", "open: a command line to run right after the shell starts, for example pi."),
            "text" to AgentToolParam("string", "send: literal text to type."),
            "keys" to AgentToolParam("string", "send: key names pressed after the text, space separated, for example \"Escape\" or \"Ctrl-C\" or \"Down Down Enter\"."),
            "wait_ms" to AgentToolParam("integer", "Longest wait for the screen to settle (default 2000, open 3000, at most 30000)."),
            "wait_for" to AgentToolParam("string", "A regular expression to wait for on the screen instead of waiting for quiet."),
            "scrollback" to AgentToolParam("integer", "read: this many lines of history above the screen (default 0, at most 200)."),
            "cols" to AgentToolParam("integer", "open: width in characters (default 100, 40-200)."),
            "rows" to AgentToolParam("integer", "open: height in lines (default 32, 10-60)."),
        ),
        required = listOf("tool_title", "action"),
        propertyOrdering = listOf("tool_title", "action", "term_id", "command", "text", "keys", "wait_ms", "wait_for", "scrollback", "cols", "rows"),
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
                val end = terminals.await(terminal, waitMs(3_000L), waitFor = waitFor, statusBaseline = baseline)
                return ok(render(terminals, terminal, end, scrollback = 0, allowUnchanged = false))
            }
            "list" -> {
                val own = terminals.list(sessionId)
                if (own.isEmpty()) return ok("no terminals open in this chat")
                return ok(own.joinToString("\n") { t ->
                    val state = if (t.exited) "exited" else "running"
                    "${t.id} · $state · ${t.cols}x${t.rows}" + (headline(terminals.status(t))?.let { " · $it" } ?: "")
                })
            }
            "send", "read", "close" -> {
                val id = args.optString("term_id").trim()
                val terminal = terminals.get(id)?.takeIf { it.sessionId == sessionId }
                    ?: return fail("no terminal '$id' in this chat (terminal action=list shows yours)")
                if (action == "close") {
                    terminals.close(id)
                    return ok("closed $id")
                }
                val baseline = terminals.baseline(terminal)
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
                val end = terminals.await(terminal, waitMs(2_000L), waitFor = waitFor, statusBaseline = baseline)
                val scrollback = args.optInt("scrollback", 0).coerceIn(0, MAX_SCROLLBACK)
                return ok(render(terminals, terminal, end, scrollback, allowUnchanged = action == "read"))
            }
            else -> return fail("unknown action '$action' (open, send, read, list, close)")
        }
    }

    private fun render(
        terminals: AgentTerminals,
        terminal: AgentTerminals.Terminal,
        end: AgentTerminals.End,
        scrollback: Int,
        allowUnchanged: Boolean,
    ): String {
        // Privacy Mode masks the user's environment-variable values on anything the model reads.
        val screen = com.openminis.app.data.EnvVarRedactor.redactIfEnabled(terminals.screen(terminal, scrollback)).first
            .let { if (it.length > MAX_SCREEN_CHARS) it.takeLast(MAX_SCREEN_CHARS) else it }
        val hash = screen.hashCode()
        val unchanged = allowUnchanged && terminal.lastScreenHashForTool == hash
        terminal.lastScreenHashForTool = hash
        val header = "[terminal ${terminal.id} · ${if (terminal.exited) "exited" else "running"} · ${terminal.cols}x${terminal.rows}]"
        val status = headline(terminals.status(terminal))?.let { "\n[status: $it]" }.orEmpty()
        return buildString {
            append(header).append('\n')
            append(if (unchanged) "[screen unchanged]" else screen.ifEmpty { "(blank screen)" })
            append(status)
            append("\n[waited: ").append(describe(end)).append(']')
        }
    }

    private fun describe(end: AgentTerminals.End) = when (end) {
        AgentTerminals.End.QUIET -> "screen quiet"
        AgentTerminals.End.MATCH -> "wait_for matched"
        AgentTerminals.End.STATUS -> "program reported done / blocked / error"
        AgentTerminals.End.EXITED -> "program exited"
        AgentTerminals.End.TIMEOUT -> "wait_ms ran out; the program may still be working"
    }

    companion object {
        const val NAME = "terminal"
        private const val MAX_WAIT_MS = 30_000L
        private const val MAX_SCROLLBACK = 200
        private const val MAX_SCREEN_CHARS = 8_000

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
