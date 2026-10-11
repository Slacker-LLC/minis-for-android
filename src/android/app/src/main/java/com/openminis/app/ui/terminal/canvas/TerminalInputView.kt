package com.openminis.app.ui.terminal.canvas

import android.content.Context
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import com.openminis.app.ui.terminal.TerminalKeyEncoder
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Invisible 1×1 EditText that captures all keyboard input — hardware keys AND
 * IME text commits — and forwards the raw bytes to the PTY master via
 * [onInput]. Port of iOS TerminalInputView (which used a UITextField as a
 * first-responder event sink).
 *
 * Key mappings (mirroring xterm/iSH):
 *   - Backspace       → 0x7F  (DEL — what a real TTY sends)
 *   - Enter           → 0x0D  (CR — ICRNL termios converts to LF)
 *   - Arrow keys      → CSI A/B/C/D  (or SS3 when applicationCursorKeys)
 *   - Tab             → 0x09
 *   - Esc             → 0x1B
 *   - Printable char  → utf-8 bytes
 *   - Ctrl+letter     → control code (via Shift state from the accessory bar)
 *
 * Uses a custom BaseInputConnection that keeps IME composing text local and
 * flushes only committed text, so the PTY never receives replaceable Pinyin
 * pre-edit updates.
 */
@Composable
fun TerminalInputView(
    onInput: (ByteArray) -> Unit,
    applicationCursorKeys: Boolean,
    modifier: Modifier = Modifier,
    controller: TerminalInputController = rememberTerminalInputController(),
    /** The IME's uncommitted text (Pinyin letters, a Japanese reading), for the screen to show at the cursor. */
    onPreedit: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val editText = remember { createHiddenEditText(context) }

    DisposableEffect(editText) {
        controller.editText = editText
        onDispose { controller.editText = null }
    }

    AndroidView(
        factory = { editText.also { it.bind(onInput, onPreedit) { applicationCursorKeys } } },
        update = { it.bind(onInput, onPreedit) { applicationCursorKeys } },
        modifier = modifier,
    )
}

/** Host-side controller: call show/hide to focus/blur the hidden EditText. */
class TerminalInputController {
    internal var editText: TerminalInputEditText? = null

    fun requestFocus() {
        editText?.let { et ->
            et.requestFocus()
            val imm = et.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.showSoftInput(et, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun clearFocus() {
        editText?.let { et ->
            val imm = et.context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
            imm?.hideSoftInputFromWindow(et.windowToken, 0)
            et.clearFocus()
        }
    }

    val isFocused: Boolean get() = editText?.isFocused == true
}

@Composable
fun rememberTerminalInputController(): TerminalInputController =
    remember { TerminalInputController() }

private fun createHiddenEditText(context: Context): TerminalInputEditText =
    TerminalInputEditText(context).apply {
        // Make invisible but still first-responder-capable.
        alpha = 0f
        isCursorVisible = false
        setTextIsSelectable(false)
        setSingleLine()
        isFocusable = true
        isFocusableInTouchMode = true
        inputType = TERMINAL_INPUT_TYPE
        imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        setBackgroundColor(0x00000000)
    }

class TerminalInputEditText @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : EditText(context, attrs) {

    private var onInputBytes: ((ByteArray) -> Unit)? = null
    private var getAppCursorMode: () -> Boolean = { false }
    // Uncommitted IME text lives here until it is committed; every change is reported so the
    // screen can show it, or typing in a Pinyin keyboard looks like nothing happens.
    private var composingText = ""
        set(value) {
            if (field != value) {
                field = value
                onPreedit?.invoke(value)
            }
        }
    private var composingCursor = 0
    private var onPreedit: ((String) -> Unit)? = null

    fun bind(onInput: (ByteArray) -> Unit, onPreedit: (String) -> Unit, appCursorMode: () -> Boolean) {
        this.onInputBytes = onInput
        this.onPreedit = onPreedit
        this.getAppCursorMode = appCursorMode
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = TERMINAL_INPUT_TYPE
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_DONE

        return object : BaseInputConnection(this, false) {
            // A terminal cannot replace text already written to its PTY. Keep
            // pre-edit text local until the IME commits it, then send only the
            // final value once.
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                composingText = ""
                composingCursor = 0
                if (!text.isNullOrEmpty()) send(text.toString().toByteArray(Charsets.UTF_8))
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                composingText = text?.toString().orEmpty()
                composingCursor = if (newCursorPosition > 0) {
                    composingText.length + newCursorPosition - 1
                } else {
                    newCursorPosition
                }.coerceIn(0, composingText.length)
                return true
            }

            override fun sendKeyEvent(event: KeyEvent?): Boolean {
                event ?: return false
                if (event.action != KeyEvent.ACTION_DOWN) return true
                if (event.keyCode == KeyEvent.KEYCODE_DEL && composingText.isNotEmpty()) {
                    deleteFromComposition(beforeLength = 1, afterLength = 0)
                    return true
                }
                return handleKeyEvent(event)
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                if (composingText.isNotEmpty()) {
                    deleteFromComposition(beforeLength, afterLength)
                    return true
                }
                repeat(beforeLength) { send(byteArrayOf(0x7F)) }
                return true
            }

            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
                if (composingText.isEmpty()) {
                    repeat(beforeLength) { send(byteArrayOf(0x7F)) }
                    return true
                }
                var start = composingCursor
                repeat(beforeLength) {
                    if (start > 0) start = Character.offsetByCodePoints(composingText, start, -1)
                }
                var end = composingCursor
                repeat(afterLength) {
                    if (end < composingText.length) end = Character.offsetByCodePoints(composingText, end, 1)
                }
                deleteFromComposition(composingCursor - start, end - composingCursor)
                return true
            }

            override fun finishComposingText(): Boolean {
                val pending = composingText
                composingText = ""
                composingCursor = 0
                if (pending.isNotEmpty()) send(pending.toByteArray(Charsets.UTF_8))
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DEL && composingText.isNotEmpty()) {
            deleteFromComposition(beforeLength = 1, afterLength = 0)
            return true
        }
        return handleKeyEvent(event) || super.onKeyDown(keyCode, event)
    }

    private fun handleKeyEvent(event: KeyEvent): Boolean {
        // The character without Ctrl/Alt applied (Shift still counts): the encoder decides what those two mean.
        val unicode = event.getUnicodeChar(event.metaState and KeyEvent.META_SHIFT_MASK)
        val bytes = TerminalKeyEncoder.encode(
            keyCode = event.keyCode,
            unicode = unicode,
            ctrl = event.isCtrlPressed,
            alt = event.isAltPressed,
            shift = event.isShiftPressed,
            applicationCursor = getAppCursorMode(),
        )
        return if (bytes != null) { send(bytes); true } else false
    }

    private fun send(bytes: ByteArray) {
        onInputBytes?.invoke(bytes)
    }

    private fun deleteFromComposition(beforeLength: Int, afterLength: Int) {
        val start = (composingCursor - beforeLength).coerceAtLeast(0)
        val end = (composingCursor + afterLength).coerceAtMost(composingText.length)
        if (start >= end) return

        var safeStart = start
        var safeEnd = end
        if (safeStart > 0 && safeStart < composingText.length &&
            Character.isHighSurrogate(composingText[safeStart - 1]) &&
            Character.isLowSurrogate(composingText[safeStart])
        ) {
            safeStart--
        }
        if (safeEnd > 0 && safeEnd < composingText.length &&
            Character.isHighSurrogate(composingText[safeEnd - 1]) &&
            Character.isLowSurrogate(composingText[safeEnd])
        ) {
            safeEnd++
        }

        composingText = composingText.removeRange(safeStart, safeEnd)
        composingCursor = safeStart
    }
}

private const val TERMINAL_INPUT_TYPE =
    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
