package com.openminis.app.runtime.terminal

import com.openminis.app.sandbox.TerminalSession
import com.openminis.app.ui.terminal.emulator.ProgramState
import com.openminis.app.ui.terminal.emulator.StatusRecord
import com.openminis.app.ui.terminal.emulator.TerminalEmulator
import com.openminis.app.ui.terminal.emulator.cursorLine
import com.openminis.app.ui.terminal.emulator.findLines
import com.openminis.app.ui.terminal.emulator.modeLine
import com.openminis.app.ui.terminal.emulator.screenTextMarked
import com.openminis.app.ui.terminal.emulator.totalLines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real terminals the agent drives: a pty-backed guest shell (the same [TerminalSession] the Terminal page uses) whose
 * output is fed to a headless [TerminalEmulator], so the agent reads the rendered screen and not a stream of escape
 * codes, and sends text and key presses. This is what lets it run an interactive program (a coding agent, an editor, a
 * REPL) the way a person at the keyboard would; plain commands stay on the pipe-based shell, which is cheaper.
 *
 * A few terminals per chat session and in total; a terminal ends with its session, with a runtime stop, or when it has
 * sat unused for a long time. "Unused" never includes a terminal with a program still running in the foreground: a long
 * build or coding agent the agent has not looked at for an hour is still working, so only an idle shell is reclaimed.
 */
class AgentTerminals(
    private val newSession: () -> TerminalSession,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxPerSession: Int = MAX_PER_SESSION,
    private val maxTotal: Int = MAX_TOTAL,
) {
    class Terminal internal constructor(
        val id: String,
        val sessionId: String,
        val session: TerminalSession,
        val emulator: TerminalEmulator,
        val createdAtMs: Long,
    ) {
        internal val lock = Any()
        @Volatile internal var lastOutputAtMs: Long = createdAtMs
        @Volatile internal var lastUsedAtMs: Long = createdAtMs
        @Volatile internal var outputBytes: Long = 0
        @Volatile var exited: Boolean = false
            internal set
        /** Hash of the screen last handed to the agent, so an unchanged screen is not sent again (kept by the tool). */
        @Volatile var lastScreenHashForTool: Int = 0
        internal val jobs = ArrayList<Job>()

        val cols: Int get() = emulator.cols
        val rows: Int get() = emulator.rows
    }

    enum class End { QUIET, MATCH, STATUS, PROMPT, EXITED, TIMEOUT }

    class LimitExceeded(message: String) : Exception(message)

    private val terminals = ConcurrentHashMap<String, Terminal>()
    private val counter = AtomicInteger(0)
    private val admission = Any()

    private val _changes = MutableStateFlow(0)

    /** Ticks whenever a terminal is opened or closed, so the Terminal page can list the agent's terminals. */
    val changes: StateFlow<Int> = _changes.asStateFlow()
    private fun changed() { _changes.value += 1 }

    fun get(id: String): Terminal? = terminals[id]

    fun list(sessionId: String? = null): List<Terminal> =
        terminals.values.filter { sessionId == null || it.sessionId == sessionId }.sortedBy { it.createdAtMs }

    /** Opens a terminal for [sessionId]; throws [LimitExceeded] when the session or the app already has as many as allowed. */
    fun open(sessionId: String, cols: Int, rows: Int): Terminal {
        val c = cols.coerceIn(MIN_COLS, MAX_COLS)
        val r = rows.coerceIn(MIN_ROWS, MAX_ROWS)
        val terminal: Terminal
        synchronized(admission) {
            sweepIdle()
            if (terminals.values.count { it.sessionId == sessionId } >= maxPerSession) {
                throw LimitExceeded("this chat already has $maxPerSession terminals open; close one first (terminal action=close)")
            }
            if (terminals.size >= maxTotal) {
                throw LimitExceeded("the app already has $maxTotal agent terminals open; close one first (terminal action=close)")
            }
            val session = newSession()
            val emulator = TerminalEmulator(c, r)
            terminal = Terminal("t${counter.incrementAndGet()}", sessionId, session, emulator, now())
            terminals[terminal.id] = terminal
        }
        changed()
        // The terminal answers the queries a program makes (device attributes, cursor position, OSC 7501 support).
        terminal.emulator.onResponse = { bytes -> terminal.session.sendRawBytes(bytes) }
        // Collect before the shell starts: the output flow has no replay, so the first prompt would be lost.
        terminal.jobs += scope.launch {
            terminal.session.outputBytes.collect { bytes ->
                synchronized(terminal.lock) { terminal.emulator.feed(bytes) }
                terminal.outputBytes += bytes.size
                terminal.lastOutputAtMs = now()
            }
        }
        terminal.jobs += scope.launch {
            terminal.session.state.collect { state ->
                if (state == TerminalSession.State.STOPPED) {
                    synchronized(terminal.lock) { terminal.emulator.onProcessExit() }
                    terminal.exited = true
                }
            }
        }
        terminal.session.start(sessionId = sessionId, initialCols = c, initialRows = r)
        return terminal
    }

    fun send(terminal: Terminal, bytes: ByteArray) {
        terminal.lastUsedAtMs = now()
        terminal.session.sendRawBytes(bytes)
    }

    fun resize(terminal: Terminal, cols: Int, rows: Int) {
        val c = cols.coerceIn(MIN_COLS, MAX_COLS)
        val r = rows.coerceIn(MIN_ROWS, MAX_ROWS)
        synchronized(terminal.lock) { terminal.emulator.resize(c, r) }
        terminal.session.setWindowSize(c, r)
    }

    /**
     * The screen as text, with [scrollbackLines] lines of history above it. With [marks], highlighted runs (a menu's selected
     * item) are wrapped in the mark characters.
     */
    fun screen(terminal: Terminal, scrollbackLines: Int = 0, marks: Boolean = false): String =
        synchronized(terminal.lock) { terminal.emulator.screenTextMarked(scrollbackLines, marks) }

    /** Where the cursor is and what the program asked of the terminal, as one line; null when there is nothing to say. */
    fun cursorAndModes(terminal: Terminal): String = synchronized(terminal.lock) {
        listOfNotNull(terminal.emulator.cursorLine(), terminal.emulator.modeLine()).joinToString(" · ")
    }

    /** A full-screen program (editor, pager, TUI) holds the screen: highlights matter there, a shell's prompt colours do not. */
    fun inFullScreen(terminal: Terminal): Boolean = synchronized(terminal.lock) { terminal.emulator.isAlternateActive }

    /** Lines of the whole kept history and the screen that match [pattern] (newest last), and how many lines there are. */
    fun find(terminal: Terminal, pattern: Regex, max: Int): Pair<List<com.openminis.app.ui.terminal.emulator.FoundLine>, Int> =
        synchronized(terminal.lock) { terminal.emulator.findLines(pattern, max) to terminal.emulator.totalLines() }

    fun application(terminal: Terminal): Pair<Boolean, Boolean> =
        synchronized(terminal.lock) { terminal.emulator.applicationCursorKeys to terminal.emulator.bracketedPaste }

    fun status(terminal: Terminal): List<StatusRecord> = terminal.emulator.programStatus.value

    /** Take this before sending input, so a "done" the program reported earlier is not mistaken for the answer. */
    fun baseline(terminal: Terminal): Set<StatusRecord> = status(terminal).toSet()

    /**
     * Waits for the terminal to have something worth reading: the screen has been quiet for [quietMs], [waitFor] shows
     * on the screen, the program reports it finished / failed / is waiting for the user (OSC 7501), the process
     * exits, or [maxMs] runs out - whichever comes first.
     */
    suspend fun await(
        terminal: Terminal,
        maxMs: Long,
        quietMs: Long = DEFAULT_QUIET_MS,
        waitFor: Regex? = null,
        /** The status records that were already there before the input being waited on was sent; see [baseline]. */
        statusBaseline: Set<StatusRecord> = baseline(terminal),
        /**
         * Wait for the shell to be back at its prompt (the command finished) instead of for a quiet screen. A build or
         * a download can be silent for a long time and still be running; "quiet" would call that done.
         */
        untilPrompt: Boolean = false,
    ): End {
        val started = now()
        terminal.lastUsedAtMs = started
        val statusAtStart = statusBaseline
        while (true) {
            if (terminal.exited) return End.EXITED
            if (waitFor != null && waitFor.containsMatchIn(screen(terminal))) return End.MATCH
            if (status(terminal).any { it !in statusAtStart && it.state.reportsTurnEnd() }) return End.STATUS
            val t = now()
            if (t - started >= maxMs) return End.TIMEOUT
            val quietFor = t - maxOf(terminal.lastOutputAtMs, started)
            if (untilPrompt) {
                // The prompt is back when nothing holds the foreground and the output has settled. The wait never
                // ends in the first moments: right after Enter the shell has not started the command yet.
                val settled = t - started >= PROMPT_MIN_WAIT_MS && quietFor >= PROMPT_SETTLE_MS
                when (terminal.session.foregroundState()) {
                    TerminalSession.ForegroundState.IDLE -> if (settled) return End.PROMPT
                    // Cannot tell: fall back to a longer quiet than the usual one.
                    TerminalSession.ForegroundState.UNKNOWN -> if (quietFor >= maxOf(quietMs, UNKNOWN_QUIET_MS)) return End.QUIET
                    TerminalSession.ForegroundState.BUSY -> Unit
                }
            } else if (quietFor >= quietMs && waitFor == null) {
                return End.QUIET
            }
            delay(POLL_MS)
        }
    }

    /** What a mouse click or wheel can do on this terminal right now. */
    enum class MouseSupport { REPORTING, ARROW_KEYS, NONE }

    fun mouseSupport(terminal: Terminal): MouseSupport = synchronized(terminal.lock) {
        when {
            terminal.emulator.mouseTracking != com.openminis.app.ui.terminal.emulator.MouseTracking.OFF -> MouseSupport.REPORTING
            terminal.emulator.isAlternateActive -> MouseSupport.ARROW_KEYS
            else -> MouseSupport.NONE
        }
    }

    /**
     * Clicks the cell at [row], [col] (one-based, as the screen text is read), [clicks] times. Only a program that asked for
     * the mouse hears it; returns false (nothing sent) for one that did not.
     */
    fun click(terminal: Terminal, row: Int, col: Int, clicks: Int): Boolean {
        val sgr: Boolean
        synchronized(terminal.lock) {
            if (terminal.emulator.mouseTracking == com.openminis.app.ui.terminal.emulator.MouseTracking.OFF) return false
            sgr = terminal.emulator.mouseSgr
        }
        val c = (col - 1).coerceIn(0, terminal.cols - 1)
        val r = (row - 1).coerceIn(0, terminal.rows - 1)
        val out = java.io.ByteArrayOutputStream()
        repeat(clicks.coerceIn(1, 3)) {
            com.openminis.app.ui.terminal.emulator.TerminalMouse.press(c, r, sgr)?.let(out::write)
            com.openminis.app.ui.terminal.emulator.TerminalMouse.release(c, r, sgr)?.let(out::write)
        }
        if (out.size() == 0) return false
        send(terminal, out.toByteArray())
        return true
    }

    /**
     * Scrolls a full-screen program: [lines] > 0 moves down the content (what the finger does going up), < 0 up. A
     * program that asked for the mouse gets wheel events, one that did not gets arrow keys. Returns false on a plain shell,
     * where the history is read with scrollback instead.
     */
    fun scroll(terminal: Terminal, lines: Int): Boolean {
        val bytes = synchronized(terminal.lock) {
            val em = terminal.emulator
            when {
                em.mouseTracking != com.openminis.app.ui.terminal.emulator.MouseTracking.OFF ->
                    com.openminis.app.ui.terminal.emulator.TerminalMouse.wheelEvents(lines, terminal.cols / 2, terminal.rows / 2, em.mouseSgr)
                em.isAlternateActive ->
                    com.openminis.app.ui.terminal.emulator.TerminalMouse.scrollKeys(lines, em.applicationCursorKeys)
                else -> return false
            }
        }
        if (bytes.isEmpty()) return false
        send(terminal, bytes)
        return true
    }

    fun close(id: String): Boolean {
        val terminal = terminals.remove(id) ?: return false
        stop(terminal)
        changed()
        return true
    }

    /** A chat session ended: nothing of it may keep running. */
    fun closeSession(sessionId: String) {
        terminals.values.filter { it.sessionId == sessionId }.forEach { close(it.id) }
    }

    fun closeAll() {
        terminals.keys.toList().forEach(::close)
    }

    private fun stop(terminal: Terminal) {
        terminal.emulator.onResponse = null
        terminal.session.stop()
        terminal.jobs.forEach { it.cancel() }
    }

    /**
     * Terminals nobody has used for [IDLE_LIMIT_MS] are closed when a new one is asked for, but only a shell at its
     * prompt (or one that has already ended): anything with a foreground program, or whose state cannot be told, stays.
     */
    private fun sweepIdle() {
        val t = now()
        terminals.values
            .filter { t - it.lastUsedAtMs > IDLE_LIMIT_MS && it.session.foregroundState() == TerminalSession.ForegroundState.IDLE }
            .forEach { close(it.id) }
    }

    companion object {
        const val MAX_PER_SESSION = 3
        const val MAX_TOTAL = 6
        const val DEFAULT_COLS = 100
        const val DEFAULT_ROWS = 32
        const val MIN_COLS = 40
        const val MAX_COLS = 200
        const val MIN_ROWS = 10
        const val MAX_ROWS = 60
        const val DEFAULT_QUIET_MS = 500L
        private const val POLL_MS = 40L
        private const val PROMPT_MIN_WAIT_MS = 400L
        private const val PROMPT_SETTLE_MS = 250L
        private const val UNKNOWN_QUIET_MS = 1_500L
        private const val IDLE_LIMIT_MS = 60L * 60 * 1000

        private fun ProgramState.reportsTurnEnd() =
            this == ProgramState.DONE || this == ProgramState.BLOCKED || this == ProgramState.ERROR

        @Volatile private var shared: AgentTerminals? = null

        /** The app-wide registry, on real terminals. */
        fun shared(context: android.content.Context): AgentTerminals =
            shared ?: synchronized(this) {
                shared ?: AgentTerminals(
                    newSession = { TerminalSession(context.applicationContext) },
                    scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO),
                ).also { shared = it }
            }

        /** The registry if it exists; used where nothing should be created just to close it. */
        fun existing(): AgentTerminals? = shared
    }
}
