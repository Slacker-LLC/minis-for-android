package com.openminis.app.debug

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.TextView
import com.openminis.app.ui.terminal.canvas.TerminalInputEditText
import java.nio.charset.StandardCharsets

/** Debug-only PTY sink for exercising the terminal's real InputConnection with a real IME. */
class TerminalInputE2EHarnessActivity : Activity() {
    private val output = StringBuilder()
    private lateinit var outputView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)

        outputView = TextView(this).apply {
            textSize = 24f
            contentDescription = "terminal-pty-output"
            text = "OUTPUT: <empty>"
        }
        val input = TerminalInputEditText(this).apply {
            alpha = 0f
            layoutParams = LinearLayout.LayoutParams(1, 1)
            isFocusable = true
            isFocusableInTouchMode = true
            bind(::receiveBytes) { false }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(outputView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))
            addView(input)
        })

        input.requestFocus()
        input.post {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun receiveBytes(bytes: ByteArray) {
        val value = String(bytes, StandardCharsets.UTF_8)
        if (value == "\u007f") {
            if (output.isNotEmpty()) {
                val previous = Character.offsetByCodePoints(output, output.length, -1)
                output.delete(previous, output.length)
            }
        } else {
            output.append(value)
        }
        runOnUiThread { outputView.text = if (output.isEmpty()) "OUTPUT: <empty>" else "OUTPUT: $output" }
    }
}
