package com.openminis.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [T-android-work-process] How one assistant turn's thinking + tool blocks are
 * presented in the transcript.
 *
 *  - [GROUPED] (default): every maximal run of consecutive thinking / tool
 *    blocks collapses into one expandable "work process" row that reports how
 *    many steps ran, auto-expands while the run is live and folds back when it
 *    ends. Ported from Eta's `AgentWorkProcess` semantics
 *    (`ui/components/ChatMessageItem.kt` @ c15de97) plus the `collapsed`
 *    transitions of `ui/app/AgentRunMessageProjector.kt` @ c15de97.
 *  - [PER_TOOL]: the pre-existing per-block presentation — one thinking card
 *    plus one `ToolCallPill` per tool call, each with its own long-press menu
 *    (re-run / copy / stop).
 *
 * The stored value is the roadmap's `stepsPresentation` key, so the two
 * spellings ("grouped" / "perTool") round-trip through SharedPreferences.
 * Anything unknown — absent key, a value written by a future build, a
 * corrupted string — falls back to GROUPED rather than failing open into a
 * half-rendered transcript.
 */
enum class StepsPresentation {
    GROUPED,
    PER_TOOL;

    /** Value persisted under [StepsPresentationPrefs.KEY_STEPS_PRESENTATION]. */
    val storedValue: String
        get() = when (this) {
            GROUPED -> "grouped"
            PER_TOOL -> "perTool"
        }

    companion object {
        /** Pure decode of a persisted `stepsPresentation` value. */
        fun fromStoredValue(raw: String?): StepsPresentation = when (raw) {
            PER_TOOL.storedValue -> PER_TOOL
            else -> GROUPED
        }
    }
}

/**
 * App-level persisted [StepsPresentation] choice. Mirrors the shape of
 * [FastModePrefs]: a SharedPreferences write plus an in-process [StateFlow] so
 * ChatScreen can collect the current value without re-reading preferences on
 * every recomposition (the chat flatten path is the hot path).
 */
object StepsPresentationPrefs {
    private const val PREFS = "minis_steps_presentation_prefs"

    /** Roadmap key name — do not rename without a migration. */
    const val KEY_STEPS_PRESENTATION = "stepsPresentation"

    private val _value = MutableStateFlow(StepsPresentation.GROUPED)
    val value: StateFlow<StepsPresentation> = _value.asStateFlow()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Read the persisted choice and publish it on [value]. Safe at app startup. */
    fun prime(context: Context) {
        _value.value = read(context)
    }

    /** Raw read — no state publication; used by [prime] and tests. */
    fun read(context: Context): StepsPresentation =
        StepsPresentation.fromStoredValue(prefs(context).getString(KEY_STEPS_PRESENTATION, null))

    fun set(context: Context, presentation: StepsPresentation) {
        prefs(context).edit().putString(KEY_STEPS_PRESENTATION, presentation.storedValue).apply()
        _value.value = presentation
    }
}
