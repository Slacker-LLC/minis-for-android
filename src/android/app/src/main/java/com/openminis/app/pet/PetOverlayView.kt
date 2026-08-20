package com.openminis.app.pet

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** What a long-press menu entry asks the service to do. */
enum class PetMenuAction { SETTINGS, TUCK, CLOSE }

/**
 * Window content for the floating pet: a speech bubble and a chat input bar
 * stacked above the sprite.
 *
 * All three live in ONE overlay window rather than separate ones. Extra windows
 * would each need their own position bookkeeping and would visibly lag behind
 * during a drag, because every WindowManager.updateViewLayout lands
 * independently. Sharing a window makes "everything follows the pet" free.
 *
 * The window is laid out WRAP_CONTENT, so showing/hiding the bubble or the
 * input bar resizes the window itself — the sprite keeps its exact pixel size
 * either way.
 */
class PetOverlayView(context: Context) : LinearLayout(context) {

    companion object {
        /** Fixed width of the chat input bar; the service centres on it. */
        const val INPUT_BAR_WIDTH_DP = 250
    }

    val sprite = PetSpriteView(context)
    private val bubble = TextView(context)
    private val inputBar = LinearLayout(context)
    private val input = EditText(context)
    private val micButton = TextView(context)
    private val sendButton = TextView(context)
    private val menuBar = LinearLayout(context)
    private val main = Handler(Looper.getMainLooper())
    private var bubbleTimeout: Runnable? = null

    /** Invoked with the trimmed, non-empty question the user submitted. */
    var onSend: ((String) -> Unit)? = null

    /** Invoked when the mic button is tapped; the service owns recording state. */
    var onVoiceToggle: (() -> Unit)? = null

    /** Invoked when a long-press menu entry is chosen. */
    var onMenuAction: ((PetMenuAction) -> Unit)? = null

    /**
     * Invoked when the user touches anywhere OUTSIDE this window.
     *
     * Only delivered while the window carries FLAG_WATCH_OUTSIDE_TOUCH, which
     * the service adds for exactly as long as the input bar or menu is open —
     * tapping elsewhere to dismiss is what everyone expects, but a pet that
     * watched every outside touch permanently would wake on every screen tap.
     */
    var onOutsideTouch: (() -> Unit)? = null

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // ACTION_OUTSIDE is delivered to the window's root view, never to the
        // sprite's own touch listener, so it has to be intercepted here. Only
        // arrives while the window is NOT_FOCUSABLE (i.e. the menu case).
        if (event.action == MotionEvent.ACTION_OUTSIDE) {
            onOutsideTouch?.invoke()
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    /**
     * Dismiss on focus loss — the input bar's "tap elsewhere to close".
     *
     * ACTION_OUTSIDE cannot do this job here: the system only delivers it to
     * windows that are NOT_FOCUSABLE, and text entry requires the window to be
     * focusable. Tapping another app therefore takes focus away instead of
     * producing an outside-touch, so focus loss is the signal to close on.
     */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus && isInputVisible()) onFocusLost?.invoke()
    }

    /** Back key closes the input bar instead of leaving it stranded open. */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        if (event.keyCode == android.view.KeyEvent.KEYCODE_BACK &&
            event.action == android.view.KeyEvent.ACTION_UP &&
            isInputVisible()
        ) {
            onFocusLost?.invoke()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /** Invoked when the focused input bar should close (focus loss / back key). */
    var onFocusLost: (() -> Unit)? = null

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        // The bubble and input bar are drawn outside the sprite's box; without
        // this the parent clips them while the window is still being resized.
        clipChildren = false
        clipToPadding = false

        bubble.apply {
            visibility = View.GONE
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 4
            setPadding(dp(10), dp(6), dp(10), dp(6))
            // Drawn in code on purpose: the patch stays resource-free so it can
            // be applied to an upstream tree without merging XML.
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(0xE0202124.toInt())
                setStroke(dp(1), 0x33FFFFFF)
            }
        }
        addView(
            bubble,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(4)
            },
        )

        buildInputBar()
        addView(
            inputBar,
            LayoutParams(dp(INPUT_BAR_WIDTH_DP), LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(4)
            },
        )

        buildMenuBar()
        addView(
            menuBar,
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(4)
            },
        )

        addView(sprite, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    private fun buildMenuBar() {
        menuBar.apply {
            visibility = View.GONE
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(2))
            background = GradientDrawable().apply {
                cornerRadius = dp(18).toFloat()
                setColor(0xF0202124.toInt())
                setStroke(dp(1), 0x33FFFFFF)
            }
        }
        listOf(
            "设置" to PetMenuAction.SETTINGS,
            "收起" to PetMenuAction.TUCK,
            "关闭" to PetMenuAction.CLOSE,
        ).forEach { (label, action) ->
            val item = TextView(context).apply {
                text = label
                setTextColor(if (action == PetMenuAction.CLOSE) 0xFFFF7A7A.toInt() else Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                setOnClickListener {
                    setMenuVisible(false)
                    onMenuAction?.invoke(action)
                }
            }
            menuBar.addView(item, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
    }

    fun setMenuVisible(visible: Boolean) {
        if (visible == (menuBar.visibility == View.VISIBLE)) return
        menuBar.visibility = if (visible) View.VISIBLE else View.GONE
        requestLayout()
    }

    fun isMenuVisible(): Boolean = menuBar.visibility == View.VISIBLE

    private fun buildInputBar() {
        inputBar.apply {
            visibility = View.GONE
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setColor(0xF0202124.toInt())
                setStroke(dp(1), 0x33FFFFFF)
            }
        }

        micButton.apply {
            text = "🎤"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setOnClickListener { onVoiceToggle?.invoke() }
        }
        inputBar.addView(micButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        input.apply {
            hint = "跟我说点什么…"
            setHintTextColor(0x80FFFFFF.toInt())
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            background = null
            maxLines = 3
            setPadding(dp(6), dp(2), dp(6), dp(2))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            imeOptions = EditorInfo.IME_ACTION_SEND
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    submit()
                    true
                } else {
                    false
                }
            }
        }
        inputBar.addView(
            input,
            LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f),
        )

        sendButton.apply {
            text = "发送"
            setTextColor(0xFF7C9BFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setOnClickListener { submit() }
        }
        inputBar.addView(sendButton, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
    }

    private fun submit() {
        val text = input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        input.setText("")
        onSend?.invoke(text)
    }

    /** Sprite size in px; the bubble is capped relative to it so it stays readable. */
    fun setSpriteSize(width: Int, height: Int) {
        sprite.layoutParams = LayoutParams(width, height)
        bubble.maxWidth = (width * 2.4f).toInt().coerceAtLeast(dp(180))
        requestLayout()
    }

    /**
     * Show a transient message. Passing a blank message hides the bubble.
     *
     * @param durationMs how long the bubble stays up; 0 keeps it until replaced.
     */
    fun showBubble(message: String, durationMs: Long = 2_600L) {
        bubbleTimeout?.let(main::removeCallbacks)
        bubbleTimeout = null

        if (message.isBlank()) {
            hideBubble()
            return
        }

        // A still-running fade-out (hideBubble's withEndAction) would hide the
        // fresh bubble as soon as it appears — cancel it and pin full alpha.
        bubble.animate().cancel()
        bubble.alpha = 1f

        bubble.text = message
        if (bubble.visibility != View.VISIBLE) {
            bubble.alpha = 0f
            bubble.visibility = View.VISIBLE
            bubble.animate().alpha(1f).setDuration(160L).start()
        }
        requestLayout()

        if (durationMs > 0) {
            bubbleTimeout = Runnable { hideBubble() }.also { main.postDelayed(it, durationMs) }
        }
    }

    fun hideBubble() {
        bubbleTimeout?.let(main::removeCallbacks)
        bubbleTimeout = null
        if (bubble.visibility == View.GONE) return
        bubble.animate().alpha(0f).setDuration(140L).withEndAction {
            bubble.visibility = View.GONE
            requestLayout()
        }.start()
    }

    fun isBubbleVisible(): Boolean = bubble.visibility == View.VISIBLE

    fun setInputVisible(visible: Boolean) {
        if (visible == (inputBar.visibility == View.VISIBLE)) return
        inputBar.visibility = if (visible) View.VISIBLE else View.GONE
        if (visible) {
            input.requestFocus()
        } else {
            input.setText("")
            input.clearFocus()
        }
        requestLayout()
    }

    fun isInputVisible(): Boolean = inputBar.visibility == View.VISIBLE

    fun focusInput() {
        input.requestFocus()
    }

    fun setInputText(text: String) {
        input.setText(text)
        input.setSelection(input.text?.length ?: 0)
    }

    /** Recording feedback: the mic turns red while the engine is listening. */
    fun setVoiceActive(active: Boolean) {
        micButton.text = if (active) "⏹" else "🎤"
        micButton.setTextColor(if (active) 0xFFFF5A5A.toInt() else Color.WHITE)
    }

    fun setBusy(busy: Boolean) {
        sendButton.isEnabled = !busy
        sendButton.setTextColor(if (busy) 0x66FFFFFF else 0xFF7C9BFF.toInt())
    }

    fun cancelPending() {
        bubbleTimeout?.let(main::removeCallbacks)
        bubbleTimeout = null
        bubble.animate().cancel()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
