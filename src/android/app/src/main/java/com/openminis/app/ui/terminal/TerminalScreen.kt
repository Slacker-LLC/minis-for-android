package com.openminis.app.ui.terminal

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import com.openminis.app.R
import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openminis.app.sandbox.TerminalSession
import com.openminis.app.terminal.MinisOpenUrlBroker
import com.openminis.app.ui.terminal.canvas.TerminalNativeViewCompose
import com.openminis.app.ui.terminal.canvas.TerminalInputView
import com.openminis.app.ui.terminal.canvas.rememberTerminalInputController
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

@Composable
fun TerminalScreen(
    terminalSession: TerminalSession,
    onBack: () -> Unit,
    initCommand: String? = null,
    /**
     * When non-null, binds this terminal to the given chat session —
     * TerminalSession.start() will chdir into /var/minis and pick up the
     * session's env vars (mirrors iOS "Open Terminal" from chat).
     */
    sessionId: String? = null,
) {
    val emulator = remember { TerminalEmulator() }
    val inputController = rememberTerminalInputController()
    val scope = rememberCoroutineScope()
    var ctrlActive by remember { mutableStateOf(false) }
    var altActive by remember { mutableStateOf(false) }

    // Pipe PTY output → emulator.
    LaunchedEffect(terminalSession) {
        terminalSession.outputBytes.collect { bytes ->
            emulator.feed(bytes)
        }
    }

    // Wire emulator responses (DSR etc.) back to the PTY.
    DisposableEffect(terminalSession, emulator) {
        emulator.onResponse = { data -> terminalSession.sendRawBytes(data) }
        onDispose { emulator.onResponse = null }
    }

    var showClearSheet by remember { mutableStateOf(false) }

    // Clear emulator when session clearOutput() ticks.
    val clearVersion by terminalSession.clearVersion.collectAsStateEffect()
    LaunchedEffect(clearVersion) {
        if (clearVersion > 0) {
            emulator.feed("\u001Bc".toByteArray())   // RIS — full reset
        }
    }

    // Start session + optional initCommand.
    LaunchedEffect(Unit) {
        if (!terminalSession.isRunning) terminalSession.start(sessionId = sessionId)
        if (!initCommand.isNullOrBlank()) {
            // Pre-fill at the prompt without newline so the user can review.
            kotlinx.coroutines.delay(500)
            terminalSession.sendText(initCommand)
        }
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

    DisposableEffect(Unit) {
        onDispose { terminalSession.stop() }
    }

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
    val accessoryBarHeightDp = 104.dp

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
        // Main content: top bar + canvas. imePadding() lifts the canvas
        // above the keyboard so it's never covered.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
                .imePadding()
                .padding(bottom = accessoryBarHeightDp),
        ) {
            Spacer(modifier = Modifier.height(52.dp))
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                // T194 part-2: native Android View backing gives us long-press
                // selection + ActionMode + ClipboardManager copy. The old
                // TerminalCanvasView is left in the package as a Compose-only
                // fallback if anything regresses with the View interop path.
                TerminalNativeViewCompose(
                    emulator = emulator,
                    onResize = { cols, rows ->
                        emulator.resize(cols, rows)
                        terminalSession.setWindowSize(cols, rows)
                    },
                    onTap = { inputController.requestFocus() },
                )
                TerminalInputView(
                    onInput = { bytes ->
                        // Any user input snaps back to live tail so typing is visible.
                        emulator.scrollOffset = 0
                        val modified = applyTerminalModifiers(bytes, ctrlActive, altActive)
                        if (modified.ctrlUsed) ctrlActive = false
                        if (modified.altUsed) altActive = false
                        terminalSession.sendRawBytes(modified.bytes)
                        return@TerminalInputView
                        terminalSession.sendRawBytes(bytes)
                    },
                    applicationCursorKeys = emulator.applicationCursorKeys,
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
                onClose = {
                    terminalSession.stop()
                    onBack()
                },
                onClear = { showClearSheet = true },
            )
        }

        // T290: accessory bar — anchored to bottom of the parent Box and
        // lifted above the IME via imePadding(). When the keyboard is
        // closed, windowInsetsPadding(navigationBars) keeps it above the
        // gesture / nav bar. No Popup, no manual IME tracking.
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
                    emulator.scrollOffset = 0
                    val modified = applyTerminalModifiers(bytes, ctrlActive, altActive)
                    if (modified.ctrlUsed) ctrlActive = false
                    if (modified.altUsed) altActive = false
                    terminalSession.sendRawBytes(modified.bytes)
                },
                onArrow = { dir ->
                    emulator.scrollOffset = 0
                    val prefix = if (emulator.applicationCursorKeys)
                        byteArrayOf(0x1B, 'O'.code.toByte())
                    else byteArrayOf(0x1B, '['.code.toByte())
                    terminalSession.sendRawBytes(prefix + byteArrayOf(dir.code.toByte()))
                },
            )
        }

        if (showClearSheet) {
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
                            terminalSession.sendRawBytes(byteArrayOf(0x15))
                            terminalSession.clearOutput()
                            emulator.feed("\u001Bc".toByteArray())
                        },
                    ),
                ),
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

// ── Tiny helper: collectAsState for StateFlow<Int> without pulling all of androidx.lifecycle ──
@Composable
private fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsStateEffect(): androidx.compose.runtime.State<T> {
    val state = remember { androidx.compose.runtime.mutableStateOf(value) }
    LaunchedEffect(this) { collect { state.value = it } }
    return state
}

// ─── Top bar ──────────────────────────────────────────────────────────────────

/** The board's bar: back chevron and label on the left, "Minis Shell" centered, Clear on the right. */
@Composable
private fun TerminalTopBar(
    onClose: () -> Unit,
    onClear: () -> Unit,
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
        Text(
            stringResource(R.string.terminal_title),
            color = chrome.fg,
            fontSize = 17.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            modifier = Modifier.align(Alignment.Center),
        )
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
