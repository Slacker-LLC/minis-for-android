package com.openminis.app.ui.terminal

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import com.openminis.app.R
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardTab
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Eject
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.PauseCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.runtime.terminal.AgentTerminals
import com.openminis.app.runtime.terminal.TerminalSessionManager
import com.openminis.app.runtime.terminal.UserTerminal
import com.openminis.app.terminal.MinisOpenUrlBroker
import com.openminis.app.ui.terminal.canvas.TerminalNativeViewCompose
import com.openminis.app.ui.terminal.canvas.TerminalInputView
import com.openminis.app.ui.terminal.canvas.rememberTerminalInputController
import com.openminis.app.ui.terminal.emulator.ProgramState
import com.openminis.app.ui.terminal.emulator.StatusRecord
import com.openminis.app.ui.terminal.emulator.sanitizeStatusText
import com.openminis.app.ui.terminal.emulator.TerminalEmulator
import com.openminis.app.ui.terminal.emulator.TerminalPalette
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.launch

// The terminal follows the app theme (docs/design/UI-DESIGN-LANGUAGE.md §3): dark keeps the
// original iOS-matched palette, light is white page + black text. The emulator's own
// default colours and ANSI palette switch with TerminalPalette.light (see TerminalTypes.kt).
private class TerminalChrome(
    val bg: Color,
    val fg: Color,
    val accent: Color,
    val accessoryBg: Color,
    val keyBg: Color,
    val keyFg: Color,
    val keyActiveBg: Color,
    val topButtonBg: Color,
)

private val DarkTerminalChrome = TerminalChrome(
    bg = Color(0xFF000000),
    fg = Color(0xFFD4D4D4),
    accent = Color(0xFF34C759),
    accessoryBg = Color(0xFF1F1F1F),
    keyBg = Color(0xFF404040),
    keyFg = Color(0xFF34C759),
    keyActiveBg = Color(0xFF007AFF),
    topButtonBg = Color(0xFF2C2C2E),
)

private val LightTerminalChrome = TerminalChrome(
    bg = Color(0xFFFFFFFF),
    fg = Color(0xFF000000),
    accent = Color(0xFF0068D6),
    accessoryBg = Color(0xFFF2F2F7),
    keyBg = Color(0xFFFFFFFF),
    keyFg = Color(0xFF000000),
    keyActiveBg = Color(0xFF0068D6),
    topButtonBg = Color(0xFFF2F2F7),
)

// A terminal reads best light-on-dark, so it does not follow a light app theme (the board's white
// terminal was a poor fit for long output). LightTerminalChrome stays only as an unused fallback
// for the palette switch below.
@Composable
private fun terminalChrome(): TerminalChrome = DarkTerminalChrome

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun TerminalScreen(
    manager: TerminalSessionManager,
    agentTerminals: AgentTerminals,
    onBack: () -> Unit,
    initCommand: String? = null,
    /**
     * When non-null, binds a new terminal to the given chat session —
     * TerminalSession.start() will chdir into /var/minis and pick up the
     * session's env vars (mirrors iOS "Open Terminal" from chat).
     */
    sessionId: String? = null,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val inputController = rememberTerminalInputController()
    var ctrlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }
    var fontSp by remember { mutableStateOf(TerminalPrefs.fontSp(context)) }

    // The terminals belong to the app (Issue #183): this page only shows them. Leaving it detaches and
    // nothing is stopped; the shells and what runs in them keep going, and output keeps filling the emulators.
    val tabs by manager.tabs.collectAsState()
    val selectedId by manager.selectedId.collectAsState()
    val maintenance by manager.maintenanceNotice.collectAsState()

    // The agent's terminals are listed too: on every open or close, and once a second for ones that ended.
    val agentChanges by agentTerminals.changes.collectAsState()
    var pollTick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            pollTick++
        }
    }
    val agentList = remember(agentChanges, pollTick) { agentTerminals.list() }

    val userTab = tabs.firstOrNull { it.id == selectedId }
    val agentTab = agentList.firstOrNull { TerminalSessionManager.agentTabId(it.id) == selectedId }
    var takenOver by remember(selectedId) { mutableStateOf(false) }

    // Open what the caller asked for once per visit; a rotation or a process restore must not open it again.
    var handled by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (handled) return@LaunchedEffect
        handled = true
        try {
            manager.openOrSelect(sessionId, initCommand)
        } catch (e: TerminalSessionManager.LimitExceeded) {
            android.widget.Toast.makeText(
                context, context.getString(R.string.terminal_tab_limit, TerminalSessionManager.MAX_TABS), android.widget.Toast.LENGTH_SHORT,
            ).show()
        }
    }

    // A selection that points at nothing (its tab was closed, or the agent's terminal went away) moves to a neighbour;
    // with nothing left to show the page closes.
    LaunchedEffect(tabs, agentList, selectedId, handled) {
        if (!handled) return@LaunchedEffect
        val known = userTab != null || agentTab != null
        if (!known) {
            val next = tabs.lastOrNull()?.id ?: agentList.lastOrNull()?.let { TerminalSessionManager.agentTabId(it.id) }
            if (next != null) manager.select(next) else onBack()
        }
    }

    // Keep the shell's view of the page: attach while shown, detach on leaving. Detaching changes nothing else.
    DisposableEffect(userTab?.id) {
        val id = userTab?.id
        if (id != null) manager.attach(id)
        onDispose { if (id != null) manager.detach(id) }
    }

    val activeEmulator: TerminalEmulator? = userTab?.emulator ?: agentTab?.emulator
    val sendRaw: (ByteArray) -> Unit = { bytes ->
        when {
            userTab != null -> userTab.session.sendRawBytes(bytes)
            agentTab != null && takenOver -> agentTerminals.send(agentTab, bytes)
        }
    }

    val programStatus = activeEmulator?.programStatus?.value.orEmpty()
    val statusLine = if (activeEmulator == null) null else programStatusLine(programStatus, activeEmulator::effectiveApp)

    var showClearSheet by remember { mutableStateOf(false) }
    var closeAsk by remember { mutableStateOf<String?>(null) }
    var renameId by remember { mutableStateOf<String?>(null) }

    fun closeTab(id: String) {
        manager.close(id)
    }

    // T-android-terminal-keyboard-3a8f5e0b: Do NOT auto-focus the hidden input
    // EditText on open. Previously a 20-tick retry loop here force-popped the
    // IME on entry (matching iOS becomeFirstResponder), but on Android that
    // caused two issues: (1) keyboard pops up unsolicited when the user just
    // wants to read terminal output, (2) the back gesture's first press hides
    // the IME, then the still-living retry loop (or a focus-restore on next
    // composition) re-pops it, requiring a second back press to actually
    // leave the screen. Android convention for read-eval shells is to let the
    // user tap the canvas (handled below via `onTap = { requestFocus() }`) or
    // the keyboard-toggle button in the accessory bar to deliberately invoke
    // the IME — that single user action handles both opening the keyboard
    // and (via the toggle) closing it, and a single back press now exits.

    // Claim the broker while the fullscreen terminal is up so ChatScreen
    // (still composed underneath this destination's stack) doesn't try to
    // present its own preview sheet on top — mirrors iOS ISHTerminalView.
    DisposableEffect(Unit) {
        MinisOpenUrlBroker.setTerminalVisible(true)
        onDispose { MinisOpenUrlBroker.setTerminalVisible(false) }
    }

    // OSC 1337 MinisOpenURL emitted by `/usr/local/bin/minis-open` is parsed
    // by TerminalEmulator and forwarded to MinisOpenUrlBroker. From the
    // standalone terminal we only route web schemes (http(s)/about) into an
    // in-app WebView preview; minis://-style chat resources need ChatScreen's
    // resolver and aren't reachable here, so we still consume them to avoid
    // leaking a stale pendingUrl back to chat on next attach.
    var previewUrl by remember { mutableStateOf<String?>(null) }
    val pendingUrl by MinisOpenUrlBroker.pendingUrl.collectAsStateEffect()
    LaunchedEffect(pendingUrl) {
        val uri = pendingUrl ?: return@LaunchedEffect
        if (MinisOpenUrlBroker.isWebScheme(uri.scheme)) {
            previewUrl = uri.toString()
        }
        MinisOpenUrlBroker.consume()
    }

    // T290: Layered layout — top bar fixed, canvas fills middle, accessory
    // bar pinned to bottom and lifted above the IME via Modifier.imePadding().
    //
    // Pre-T290 this was an off-window Popup + manually-tracked IME height
    // built around a Pixel/Gboard PAN-mode quirk. That misfired on pixel6
    // (bar still under the keyboard). With windowSoftInputMode=adjustResize
    // already set in the manifest, edge-to-edge enabled, and modern Pixel
    // builds reporting WindowInsets.ime correctly, a plain in-window Box +
    // imePadding does the right thing without any custom tracking.
    val showAccessory = userTab != null || (agentTab != null && takenOver)
    val accessoryBarHeightDp = if (showAccessory) 104.dp else 0.dp

    val chrome = terminalChrome()
    // Idempotent global: the emulator resolves default/ANSI colours at draw time, so
    // flipping this recolours existing scrollback as well as new output.
    TerminalPalette.light = false

    // Status-bar icons must be light on the black page, whatever the app theme says.
    val hostView = LocalView.current
    val hostWindow = (hostView.context as? android.app.Activity)?.window
    val barController = remember(hostWindow, hostView) {
        hostWindow?.let { androidx.core.view.WindowCompat.getInsetsController(it, hostView) }
    }
    // The activity re-applies its own bar style after a theme change, so set ours again one frame
    // later as well as immediately; restore the page's setting when the terminal closes.
    DisposableEffect(barController) {
        val previous = barController?.isAppearanceLightStatusBars
        barController?.isAppearanceLightStatusBars = false
        onDispose { if (previous != null) barController.isAppearanceLightStatusBars = previous }
    }
    LaunchedEffect(barController) {
        androidx.compose.runtime.withFrameNanos { }
        barController?.isAppearanceLightStatusBars = false
    }

    Box(modifier = Modifier.fillMaxSize().background(chrome.bg)) {
        // Main content: top bar + tabs + canvas. imePadding() lifts the canvas
        // above the keyboard so it's never covered.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .imePadding()
                .padding(bottom = accessoryBarHeightDp),
        ) {
            Spacer(modifier = Modifier.height(52.dp))
            TerminalTabBar(
                tabs = tabs,
                agentTabs = agentList,
                selectedId = selectedId,
                onSelect = { manager.select(it) },
                onClose = { id -> if (manager.isBusy(id)) closeAsk = id else closeTab(id) },
                onRename = { renameId = it },
                onNew = {
                    try {
                        manager.open(sessionId = userTab?.sessionId)
                    } catch (e: TerminalSessionManager.LimitExceeded) {
                        android.widget.Toast.makeText(
                            context, context.getString(R.string.terminal_tab_limit, TerminalSessionManager.MAX_TABS), android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (userTab != null) {
                    // One view per tab: switching tabs builds a fresh view over that tab's emulator, so what a tab
                    // shows (screen, scrollback, selection) is never mixed up with another's.
                    key(userTab.id) {
                        // T194 part-2: native Android View backing gives us long-press
                        // selection + ActionMode + ClipboardManager copy. The old
                        // TerminalCanvasView is left in the package as a Compose-only
                        // fallback if anything regresses with the View interop path.
                        TerminalNativeViewCompose(
                            emulator = userTab.emulator,
                            fontSizeSp = fontSp,
                            onResize = { cols, rows ->
                                synchronized(userTab.lock) { userTab.emulator.resize(cols, rows) }
                                userTab.session.setWindowSize(cols, rows)
                            },
                            onTap = { inputController.requestFocus() },
                            onSend = { bytes ->
                                userTab.emulator.scrollOffset = 0
                                userTab.session.sendRawBytes(bytes)
                            },
                            onFontSizeChange = { sp, commit ->
                                fontSp = sp
                                if (commit) TerminalPrefs.setFontSp(context, sp)
                            },
                        )
                    }
                    if (userTab.exited.collectAsState().value) {
                        EndedShellBar(onRestart = { manager.restart(userTab.id) }, modifier = Modifier.align(Alignment.BottomCenter))
                    }
                } else if (agentTab != null) {
                    AgentTerminalView(
                        terminal = agentTab,
                        agentTerminals = agentTerminals,
                        takenOver = takenOver,
                        onTakeOver = { takenOver = true },
                        onRelease = { takenOver = false },
                    )
                }
                TerminalInputView(
                    onInput = { bytes ->
                        // Any user input snaps back to live tail so typing is visible.
                        activeEmulator?.scrollOffset = 0
                        val modified = applyTerminalModifiers(bytes, ctrlActive, altActive)
                        if (modified.ctrlUsed) ctrlActive = false
                        if (modified.altUsed) altActive = false
                        sendRaw(modified.bytes)
                    },
                    applicationCursorKeys = activeEmulator?.applicationCursorKeys ?: false,
                    controller = inputController,
                    modifier = Modifier.size(1.dp),
                )
            }
        }

        // Top bar pinned at top — never moves with IME.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.systemBars)
                .height(52.dp)
                .background(chrome.bg),
        ) {
            TerminalTopBar(
                status = statusLine,
                // Leaving the page never stops a terminal: closing a tab is its own button.
                onClose = onBack,
                onClear = { showClearSheet = true },
                canClear = userTab != null,
            )
        }

        // T290: accessory bar — anchored to bottom of the parent Box and
        // lifted above the IME via imePadding(). When the keyboard is
        // closed, windowInsetsPadding(navigationBars) keeps it above the
        // gesture / nav bar. No Popup, no manual IME tracking.
        if (showAccessory) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .imePadding()
                    .windowInsetsPadding(WindowInsets.navigationBars),
            ) {
                KeyboardAccessoryBar(
                    ctrlActive = ctrlActive,
                    altActive = altActive,
                    keyboardVisible = inputController.isFocused,
                    onCtrlToggle = { ctrlActive = !ctrlActive },
                    onAltToggle = { altActive = !altActive },
                    onToggleKeyboard = {
                        if (inputController.isFocused) inputController.clearFocus()
                        else inputController.requestFocus()
                    },
                    onSendRaw = { bytes ->
                        activeEmulator?.scrollOffset = 0
                        val modified = applyTerminalModifiers(bytes, ctrlActive, altActive)
                        if (modified.ctrlUsed) ctrlActive = false
                        if (modified.altUsed) altActive = false
                        sendRaw(modified.bytes)
                    },
                    onArrow = { dir ->
                        activeEmulator?.scrollOffset = 0
                        val prefix = if (activeEmulator?.applicationCursorKeys == true)
                            byteArrayOf(0x1B, 'O'.code.toByte())
                        else byteArrayOf(0x1B, '['.code.toByte())
                        sendRaw(prefix + byteArrayOf(dir.code.toByte()))
                    },
                )
            }
        }

        if (showClearSheet && userTab != null) {
            com.openminis.app.ui.components.MinisActionSheet(
                onDismiss = { showClearSheet = false },
                actions = listOf(
                    com.openminis.app.ui.components.MinisAction(
                        label = stringResource(R.string.terminal_clear),
                        destructive = true,
                        onClick = {
                            // T310: send Ctrl+U (NAK, 0x15) so readline kills any
                            // half-typed line in the shell. Otherwise those chars
                            // stay in the line buffer and get prepended to the
                            // user's next command after the visual clear.
                            userTab.session.sendRawBytes(byteArrayOf(0x15))
                            userTab.session.clearOutput()
                            synchronized(userTab.lock) { userTab.emulator.feed("\u001Bc".toByteArray()) }
                        },
                    ),
                ),
            )
        }

        // Closing a tab that has a program running ends the program: ask once. An idle shell closes without asking.
        closeAsk?.let { id ->
            com.openminis.app.ui.components.MinisAlertDialog(
                onDismissRequest = { closeAsk = null },
                title = stringResource(R.string.terminal_close_busy_title),
                text = stringResource(R.string.terminal_close_busy_body),
                confirmText = stringResource(R.string.terminal_close_busy_confirm),
                isDestructive = true,
                onConfirm = {
                    closeAsk = null
                    closeTab(id)
                },
            )
        }

        renameId?.let { id ->
            val tab = tabs.firstOrNull { it.id == id }
            if (tab == null) {
                renameId = null
            } else {
                RenameTerminalDialog(
                    current = tab.title.collectAsState().value,
                    onDismiss = { renameId = null },
                    onConfirm = { name ->
                        manager.rename(id, name)
                        renameId = null
                    },
                )
            }
        }

        // The runtime ended terminals that had a program running (rootfs repair, mount change): say so, once.
        maintenance?.let { notice ->
            com.openminis.app.ui.components.MinisAlertDialog(
                onDismissRequest = { manager.dismissMaintenanceNotice() },
                title = { Text(stringResource(R.string.terminal_maintenance_title)) },
                text = { Text(stringResource(R.string.terminal_maintenance_body, notice.busy)) },
                confirmButton = {
                    com.openminis.app.ui.components.MinisTextButton(onClick = { manager.dismissMaintenanceNotice() }) {
                        Text(stringResource(R.string.terminal_maintenance_ok))
                    }
                },
            )
        }

        previewUrl?.let { url ->
            com.openminis.app.ui.components.UrlPreviewSheet(
                url = url,
                onDismiss = { previewUrl = null },
            )
        }
    }
}

// ─── Tabs ─────────────────────────────────────────────────────────────────────

/** Tab strip: one chip per terminal (name, status dot, close), the agent's terminals read-only, and "+" at the end. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TerminalTabBar(
    tabs: List<UserTerminal>,
    agentTabs: List<AgentTerminals.Terminal>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    onRename: (String) -> Unit,
    onNew: () -> Unit,
) {
    val chrome = terminalChrome()
    val closeLabel = stringResource(R.string.terminal_tab_close)
    val newLabel = stringResource(R.string.terminal_tab_new)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            val selected = tab.id == selectedId
            val title by tab.title.collectAsState()
            val exited by tab.exited.collectAsState()
            val dot = programStatusLine(tab.emulator.programStatus.value, tab.emulator::effectiveApp)?.color
            TabChip(
                title = title,
                selected = selected,
                dim = exited,
                dot = dot,
                onClick = { onSelect(tab.id) },
                onLongClick = { onRename(tab.id) },
                closeLabel = closeLabel,
                onClose = { onClose(tab.id) },
            )
        }
        agentTabs.forEach { terminal ->
            val id = TerminalSessionManager.agentTabId(terminal.id)
            val dot = programStatusLine(terminal.emulator.programStatus.value, terminal.emulator::effectiveApp)?.color
            TabChip(
                title = stringResource(R.string.terminal_agent_badge) + " · " + terminal.id,
                selected = id == selectedId,
                dim = terminal.exited,
                dot = dot,
                onClick = { onSelect(id) },
                onLongClick = null,
                closeLabel = null,
                onClose = null,
            )
        }
        Text(
            "+",
            color = chrome.accent,
            fontSize = 22.sp,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onNew)
                .semantics { contentDescription = newLabel }
                .padding(horizontal = 12.dp, vertical = 2.dp),
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TabChip(
    title: String,
    selected: Boolean,
    dim: Boolean,
    dot: Color?,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    closeLabel: String?,
    onClose: (() -> Unit)?,
) {
    val chrome = terminalChrome()
    val bg = if (selected) chrome.keyActiveBg.copy(alpha = 0.35f) else chrome.topButtonBg
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(start = 10.dp, end = if (onClose != null) 2.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dot != null) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            title,
            color = chrome.fg.copy(alpha = if (dim) 0.5f else 1f),
            fontSize = 14.sp,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 120.dp).padding(vertical = 6.dp),
        )
        if (onClose != null && closeLabel != null) {
            Text(
                "×",
                color = chrome.fg.copy(alpha = 0.7f),
                fontSize = 18.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onClose)
                    .semantics { contentDescription = closeLabel }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun RenameTerminalDialog(current: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var name by remember { mutableStateOf(current) }
    com.openminis.app.ui.components.MinisAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.terminal_rename_title)) },
        text = {
            com.openminis.app.ui.components.DialogTextField(
                value = name,
                onValueChange = { name = it.take(UserTerminal.MAX_TITLE) },
                singleLine = true,
            )
        },
        dismissButton = {
            com.openminis.app.ui.components.MinisTextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        confirmButton = {
            com.openminis.app.ui.components.MinisTextButton(onClick = { onConfirm(name) }) { Text(stringResource(R.string.terminal_rename_action)) }
        },
    )
}

/** Shown over a tab whose shell ended: the screen stays readable, and "Restart" starts a new shell in the same tab. */
@Composable
private fun EndedShellBar(onRestart: () -> Unit, modifier: Modifier = Modifier) {
    val chrome = terminalChrome()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(chrome.accessoryBg)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.terminal_exited), color = chrome.fg.copy(alpha = 0.8f), fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text(
            stringResource(R.string.terminal_restart),
            color = chrome.accent,
            fontSize = 16.sp,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onRestart).padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

/**
 * The agent's terminal as a read-only text snapshot, refreshed while shown. It is not a second view over the agent's
 * emulator (that one is fed on another thread); "Take over" lets the user type into it, and "Release" gives it back.
 */
@Composable
private fun AgentTerminalView(
    terminal: AgentTerminals.Terminal,
    agentTerminals: AgentTerminals,
    takenOver: Boolean,
    onTakeOver: () -> Unit,
    onRelease: () -> Unit,
) {
    val chrome = terminalChrome()
    val text by produceState("", terminal) {
        while (true) {
            value = runCatching { agentTerminals.screen(terminal, scrollbackLines = 200) }.getOrDefault("")
            kotlinx.coroutines.delay(400)
        }
    }
    val scroll = rememberScrollState()
    LaunchedEffect(text) { scroll.scrollTo(scroll.maxValue) }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().background(chrome.accessoryBg).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.terminal_agent_readonly),
                color = chrome.fg.copy(alpha = 0.8f),
                fontSize = 13.sp,
                modifier = Modifier.weight(1f),
            )
            Text(
                stringResource(if (takenOver) R.string.terminal_agent_release else R.string.terminal_agent_takeover),
                color = chrome.accent,
                fontSize = 16.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = if (takenOver) onRelease else onTakeOver)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
        Text(
            text,
            color = chrome.fg,
            style = TextStyle(fontFamily = JetBrainsMonoFontFamily, fontSize = 12.sp),
            modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

// ── Tiny helper: collectAsState for StateFlow<Int> without pulling all of androidx.lifecycle ──
@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateEffect(): androidx.compose.runtime.State<T> {
    val state = remember { androidx.compose.runtime.mutableStateOf(value) }
    LaunchedEffect(this) { collect { state.value = it } }
    return state
}

/** What the top bar shows for the terminal's OSC 7501 records: a state dot and one line of text. */
internal class ProgramStatusLine(val color: Color, val text: String)

private val STATUS_PRIORITY = listOf(
    ProgramState.BLOCKED, ProgramState.ERROR, ProgramState.WORKING, ProgramState.DONE, ProgramState.IDLE,
)

internal fun programStatusLine(records: List<StatusRecord>, effectiveApp: (StatusRecord) -> String?): ProgramStatusLine? {
    // Most urgent state wins; among equals the most recently updated record (last in the list).
    val record = records.reversed().minByOrNull { STATUS_PRIORITY.indexOf(it.state) } ?: return null
    val color = when (record.state) {
        ProgramState.BLOCKED -> Color(0xFFFF9F0A)
        ProgramState.ERROR -> Color(0xFFFF453A)
        ProgramState.WORKING -> Color(0xFF0A84FF)
        ProgramState.DONE -> Color(0xFF34C759)
        ProgramState.IDLE -> Color(0xFF8E8E93)
    }
    val text = sanitizeStatusText(record.msg ?: record.title ?: effectiveApp(record) ?: "").trim()
    return ProgramStatusLine(color, text)
}

// ─── Top bar ──────────────────────────────────────────────────────────────────

/** The board's bar: back chevron and label on the left, "Minis Shell" centered, Clear on the right. */
@Composable
private fun TerminalTopBar(
    status: ProgramStatusLine?,
    onClose: () -> Unit,
    onClear: () -> Unit,
    canClear: Boolean = true,
) {
    val chrome = terminalChrome()
    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(chrome.bg)
            .padding(horizontal = 4.dp),
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                .clickable(onClick = onClose)
                .padding(vertical = 10.dp)
                .padding(start = 4.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = stringResource(R.string.common_close),
                tint = chrome.accent,
                modifier = Modifier.size(30.dp),
            )
            Text(stringResource(R.string.back), color = chrome.accent, fontSize = 17.sp, maxLines = 1)
        }
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 96.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                stringResource(R.string.terminal_title),
                color = chrome.fg,
                fontSize = 17.sp,
                fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )
            if (status != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(status.color))
                    if (status.text.isNotEmpty()) {
                        Spacer(Modifier.width(5.dp))
                        Text(
                            status.text,
                            color = chrome.fg.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (canClear) {
            Text(
                stringResource(R.string.terminal_clear),
                color = chrome.accent,
                fontSize = 17.sp,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .clickable(onClick = onClear)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun CircularIconButton(
    icon: ImageVector,
    contentDescription: String,
    tint: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(terminalChrome().topButtonBg)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(18.dp),
        )
    }
}

// ─── Keyboard accessory bar ───────────────────────────────────────────────────

/**
 * Two rows of equal, thumb-sized keys. Top: Esc Tab Ctrl Alt and the arrows (Ctrl and Alt stay lit
 * until the next key, and the arrows repeat while held). Bottom: the characters that are a chore on
 * a phone keyboard, Enter, Ctrl-C, and the keyboard toggle.
 */
@Composable
private fun KeyboardAccessoryBar(
    ctrlActive: Boolean,
    altActive: Boolean,
    keyboardVisible: Boolean,
    onCtrlToggle: () -> Unit,
    onAltToggle: () -> Unit,
    onToggleKeyboard: () -> Unit,
    onSendRaw: (ByteArray) -> Unit,
    onArrow: (Char) -> Unit,
) {
    val chrome = terminalChrome()
    fun text(s: String) = onSendRaw(s.toByteArray())
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(chrome.accessoryBg)
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TermKey("Esc", Modifier.weight(1f)) { onSendRaw(byteArrayOf(0x1B)) }
            TermKey("Tab", Modifier.weight(1f)) { onSendRaw(byteArrayOf(0x09)) }
            TermKey("Ctrl", Modifier.weight(1f), active = ctrlActive, onClick = onCtrlToggle)
            TermKey("Alt", Modifier.weight(1f), active = altActive, onClick = onAltToggle)
            TermKey("\u2190", Modifier.weight(1f), repeat = true) { onArrow('D') }
            TermKey("\u2193", Modifier.weight(1f), repeat = true) { onArrow('B') }
            TermKey("\u2191", Modifier.weight(1f), repeat = true) { onArrow('A') }
            TermKey("\u2192", Modifier.weight(1f), repeat = true) { onArrow('C') }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TermKey("/", Modifier.weight(1f), plain = true) { text("/") }
            TermKey("-", Modifier.weight(1f), plain = true) { text("-") }
            TermKey("|", Modifier.weight(1f), plain = true) { text("|") }
            TermKey("~", Modifier.weight(1f), plain = true) { text("~") }
            TermKey("_", Modifier.weight(1f), plain = true) { text("_") }
            TermKey("\u23CE", Modifier.weight(1f)) { onSendRaw(byteArrayOf(0x0D)) }
            TermKey("^C", Modifier.weight(1f), danger = true) { onSendRaw(byteArrayOf(0x03)) }
            TermKey(
                if (keyboardVisible) "\u2328\u2193" else "\u2328",
                Modifier.weight(1f),
                onClick = onToggleKeyboard,
            )
        }
    }
}

/** One 44dp key. [repeat] keeps firing while the finger stays down; [active] is a lit sticky modifier. */
@Composable
private fun TermKey(
    label: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    plain: Boolean = false,
    danger: Boolean = false,
    repeat: Boolean = false,
    onClick: () -> Unit,
) {
    val chrome = terminalChrome()
    val bg = when {
        active -> chrome.keyActiveBg
        plain -> Color(0xFF2A2A2E)
        else -> Color(0xFF3A3A3F)
    }
    val fg = when {
        active -> Color.White
        danger -> Color(0xFFFF6961)
        else -> Color(0xFFE8E8EA)
    }
    val currentClick = androidx.compose.runtime.rememberUpdatedState(onClick)
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .then(
                if (repeat) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                currentClick.value()
                                kotlinx.coroutines.coroutineScope {
                                    val job = launch {
                                        kotlinx.coroutines.delay(350)
                                        while (true) {
                                            currentClick.value()
                                            kotlinx.coroutines.delay(70)
                                        }
                                    }
                                    try { tryAwaitRelease() } finally { job.cancel() }
                                }
                            },
                        )
                    }
                } else {
                    Modifier.clickable { currentClick.value() }
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = fg,
            style = TextStyle(fontFamily = JetBrainsMonoFontFamily, fontSize = 16.sp),
            maxLines = 1,
        )
    }
}
