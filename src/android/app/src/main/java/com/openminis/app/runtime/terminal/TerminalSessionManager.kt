package com.openminis.app.runtime.terminal

import com.openminis.app.sandbox.TerminalSession
import com.openminis.app.service.TerminalForegroundService
import com.openminis.app.ui.terminal.emulator.TerminalEmulator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

/**
 * One terminal tab of the user: a PTY-backed shell, the emulator that renders it, and a name. The session and the
 * emulator belong to the app ([TerminalSessionManager]), not to the Terminal page, so leaving the page leaves the
 * shell and whatever runs in it exactly as it was.
 */
class UserTerminal internal constructor(
    val id: String,
    /** The chat session this terminal is bound to (its workspace), or null for the plain shell. */
    val sessionId: String?,
    val session: TerminalSession,
    val emulator: TerminalEmulator,
    val createdAtMs: Long,
    initialTitle: String,
) {
    private val _title = MutableStateFlow(initialTitle)
    val title: StateFlow<String> = _title.asStateFlow()

    private val _exited = MutableStateFlow(false)

    /** True once the shell ended (the user typed `exit`, or the runtime stopped it). The tab stays until closed. */
    val exited: StateFlow<Boolean> = _exited.asStateFlow()

    internal val jobs = ArrayList<Job>()

    /** Serialises feeding the emulator from the output collector with a restart. */
    internal val lock = Any()

    fun rename(name: String) { _title.value = name.trim().take(MAX_TITLE).ifEmpty { _title.value } }

    internal fun markExited(value: Boolean) { _exited.value = value }

    companion object { const val MAX_TITLE = 32 }
}

/** Something to keep the process alive while terminals run (the foreground service); a no-op in tests. */
fun interface TerminalKeepAlive {
    /** [count] is the number of terminals, the user's and the agent's, with a live process; 0 means stop. */
    fun onRunningTerminals(count: Int)

    object None : TerminalKeepAlive { override fun onRunningTerminals(count: Int) = Unit }
}

/**
 * The user's terminals, owned by the app (Issue #183). The Terminal page only attaches to and detaches from them:
 * output keeps flowing into each emulator while nothing is looking, so coming back shows the screen and scrollback as
 * they are. A shell ends only when the user closes its tab, when it exits on its own, when the user ends all of them
 * from the notification, or when the runtime is stopped for maintenance (the UI is told, see [maintenanceNotice]).
 *
 * [scope] is where output is collected and fed to the emulators. The emulators are read by the UI thread, so in the app
 * it is the main dispatcher, the same thread the Terminal page used to feed them on.
 */
class TerminalSessionManager(
    private val newSession: () -> TerminalSession,
    private val scope: CoroutineScope,
    private val keepAlive: TerminalKeepAlive = TerminalKeepAlive.None,
    private val now: () -> Long = System::currentTimeMillis,
    private val maxTabs: Int = MAX_TABS,
    private val titleFor: (Int) -> String = { "Terminal $it" },
) {
    class LimitExceeded(message: String) : Exception(message)

    /** The runtime stopped terminals for maintenance; [busy] of them had a program running. */
    data class MaintenanceNotice(val busy: Int, val atMs: Long)

    private val counter = AtomicInteger(0)
    private val lock = Any()

    private val _tabs = MutableStateFlow<List<UserTerminal>>(emptyList())
    val tabs: StateFlow<List<UserTerminal>> = _tabs.asStateFlow()

    private val _selectedId = MutableStateFlow<String?>(null)
    val selectedId: StateFlow<String?> = _selectedId.asStateFlow()

    private val _maintenance = MutableStateFlow<MaintenanceNotice?>(null)
    val maintenanceNotice: StateFlow<MaintenanceNotice?> = _maintenance.asStateFlow()

    private val attached = java.util.concurrent.ConcurrentHashMap<String, Int>()

    init {
        TerminalSession.maintenanceListener = { busy -> _maintenance.value = MaintenanceNotice(busy, now()) }
    }

    fun get(id: String): UserTerminal? = _tabs.value.firstOrNull { it.id == id }

    fun selected(): UserTerminal? = _selectedId.value?.let(::get)

    /**
     * Opens a terminal and makes it the selected tab. [initCommand] is typed at the prompt without Enter, so the user
     * can review it. Throws [LimitExceeded] at the tab limit.
     */
    fun open(sessionId: String? = null, initCommand: String? = null, cols: Int = TerminalSession.DEFAULT_COLS, rows: Int = TerminalSession.DEFAULT_ROWS): UserTerminal {
        val terminal: UserTerminal
        synchronized(lock) {
            if (_tabs.value.size >= maxTabs) {
                throw LimitExceeded("at most $maxTabs terminals can be open; close one first")
            }
            val n = counter.incrementAndGet()
            terminal = UserTerminal("u$n", sessionId, newSession(), TerminalEmulator(cols, rows), now(), titleFor(n))
            _tabs.value = _tabs.value + terminal
            _selectedId.value = terminal.id
        }
        wire(terminal)
        start(terminal, initCommand, cols, rows)
        publishRunning()
        return terminal
    }

    private fun wire(terminal: UserTerminal) {
        // The emulator answers the queries a program makes (device attributes, cursor position, OSC 7501 support).
        terminal.emulator.onResponse = { bytes -> terminal.session.sendRawBytes(bytes) }
        // Collect before the shell starts: the output flow has no replay, so the first prompt would be lost.
        terminal.jobs += scope.launch {
            terminal.session.outputBytes.collect { bytes -> synchronized(terminal.lock) { terminal.emulator.feed(bytes) } }
        }
        terminal.jobs += scope.launch {
            terminal.session.clearVersion.collect { v ->
                if (v > 0) synchronized(terminal.lock) { terminal.emulator.feed("\u001Bc".toByteArray()) }
            }
        }
        terminal.jobs += scope.launch {
            terminal.session.state.collect { state ->
                if (state == TerminalSession.State.STOPPED) {
                    synchronized(terminal.lock) { terminal.emulator.onProcessExit() }
                    terminal.markExited(true)
                } else if (state == TerminalSession.State.RUNNING || state == TerminalSession.State.BOOTING) {
                    terminal.markExited(false)
                }
                publishRunning()
            }
        }
    }

    private fun start(terminal: UserTerminal, initCommand: String?, cols: Int, rows: Int) {
        terminal.session.start(sessionId = terminal.sessionId, initialCols = cols, initialRows = rows)
        if (!initCommand.isNullOrBlank()) {
            terminal.jobs += scope.launch {
                kotlinx.coroutines.delay(INIT_COMMAND_DELAY_MS)
                terminal.session.sendText(initCommand)
            }
        }
    }

    /** Selects a user tab, or an agent tab by its [agentTabId] (the agent's terminals are not owned here). */
    fun select(id: String) {
        if (get(id) != null || id.startsWith(AGENT_TAB_PREFIX)) _selectedId.value = id
    }

    fun rename(id: String, name: String) { get(id)?.rename(name) }

    /** Start a new shell in a tab whose shell ended. Keeps the tab, its name and its scrollback. */
    fun restart(id: String) {
        val terminal = get(id) ?: return
        if (terminal.session.isRunning) return
        terminal.markExited(false)
        start(terminal, null, terminal.emulator.cols, terminal.emulator.rows)
    }

    /**
     * Whether closing this tab would end a program: one is running in the foreground, or the session cannot say.
     * An idle shell, or one that already ended, is not busy.
     */
    fun isBusy(id: String): Boolean {
        val terminal = get(id) ?: return false
        return terminal.session.isRunning && terminal.session.foregroundState() != TerminalSession.ForegroundState.IDLE
    }

    /** Number of tabs with a program running (or unknown), for the "end all" and maintenance prompts. */
    fun busyCount(): Int = _tabs.value.count { isBusy(it.id) }

    /** Number of tabs whose shell is alive. */
    fun runningCount(): Int = _tabs.value.count { it.session.isRunning || it.session.state.value == TerminalSession.State.BOOTING }

    /** Closes the tab and ends its shell. Confirming that a busy tab may go is the caller's job ([isBusy]). */
    fun close(id: String): Boolean {
        val terminal: UserTerminal
        synchronized(lock) {
            terminal = get(id) ?: return false
            val remaining = _tabs.value - terminal
            _tabs.value = remaining
            if (_selectedId.value == id) _selectedId.value = remaining.lastOrNull()?.id
        }
        stop(terminal)
        attached.remove(id)
        publishRunning()
        return true
    }

    /** Ends every user terminal (the "end all" button on the notification). */
    fun closeAll() {
        _tabs.value.map { it.id }.forEach(::close)
    }

    private fun stop(terminal: UserTerminal) {
        terminal.emulator.onResponse = null
        terminal.session.stop()
        terminal.jobs.forEach { it.cancel() }
    }

    /** The page shows this tab. Attaching changes nothing about the shell. */
    fun attach(id: String) { attached.merge(id, 1, Int::plus) }

    /** The page stopped showing this tab. The shell, its program and its output are untouched. */
    fun detach(id: String) { attached.computeIfPresent(id) { _, n -> (n - 1).takeIf { it > 0 } } }

    fun attachedCount(id: String): Int = attached[id] ?: 0

    fun dismissMaintenanceNotice() { _maintenance.value = null }

    /** Called when the agent's terminals change too, so one count covers both. */
    fun refreshKeepAlive() = publishRunning()

    private fun publishRunning() {
        val agent = AgentTerminals.existing()?.list()?.count { !it.exited } ?: 0
        keepAlive.onRunningTerminals(runningCount() + agent)
    }

    companion object {
        const val MAX_TABS = 8
        const val AGENT_TAB_PREFIX = "agent:"
        fun agentTabId(agentTerminalId: String) = AGENT_TAB_PREFIX + agentTerminalId
        private const val INIT_COMMAND_DELAY_MS = 500L

        @Volatile private var shared: TerminalSessionManager? = null

        /** The app-wide manager, on real terminals and with the foreground service as its keep-alive. */
        fun shared(context: android.content.Context): TerminalSessionManager =
            shared ?: synchronized(this) {
                shared ?: run {
                    val app = context.applicationContext
                    TerminalSessionManager(
                        newSession = { TerminalSession(app) },
                        scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate),
                        keepAlive = TerminalForegroundService.keepAlive(app),
                        titleFor = { app.getString(com.openminis.app.R.string.terminal_tab_default, it) },
                    )
                }.also { shared = it }
            }

        fun existing(): TerminalSessionManager? = shared
    }
}
