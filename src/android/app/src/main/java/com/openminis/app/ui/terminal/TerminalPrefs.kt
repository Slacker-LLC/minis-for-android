package com.openminis.app.ui.terminal

import android.content.Context
import androidx.core.content.edit
import com.openminis.app.ui.terminal.emulator.TerminalFontScale

/** The terminal's text size, kept between launches (pinch to change). */
object TerminalPrefs {
    private const val PREFS = "terminal_prefs"
    private const val KEY_FONT_SP = "font_sp"

    fun fontSp(context: Context): Float =
        runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY_FONT_SP, TerminalFontScale.DEFAULT_SP) }
            .getOrDefault(TerminalFontScale.DEFAULT_SP)
            .coerceIn(TerminalFontScale.MIN_SP, TerminalFontScale.MAX_SP)

    fun setFontSp(context: Context, sp: Float) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putFloat(KEY_FONT_SP, sp.coerceIn(TerminalFontScale.MIN_SP, TerminalFontScale.MAX_SP))
            }
        }
    }
}
