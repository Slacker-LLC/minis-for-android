package com.openminis.app.xposed.hyperos

import android.content.Intent

/**
 * [T-eta-xposed-groups] The shape of a HyperOS "screen recognition" request, as values.
 *
 * Ported from Eta `hook/hyperos/HyperOsScreenSearchRequest.kt` (Mangi-11/Eta @ c15de97);
 * attribution in THIRD_PARTY_LICENSES.md. Eta reads four fields off the Intent and requires all of
 * them, because the same action (`ACTION_ASSIST`) is used for every assistant request on those
 * ROMs: matching on the action alone would hijack the assistant button as well as the gesture bar.
 *
 * The values are extracted from the Intent in one small function and the decision is made on plain
 * strings, so the rule itself can be tested without an Android runtime.
 */
data class ScreenSearchRequest(
    val action: String?,
    val functionKey: String?,
    val triggerType: String?,
    val startFrom: String?,
) {
    companion object {
        const val FUNCTION_KEY = "voice_assist_function_key"
        const val SCREEN_RECOGNITION = "start_screen_recognition"
        const val TRIGGER_TYPE_KEY = "triggerType"
        const val NAV_LONG_PRESS = "NavLongPress"
        const val START_FROM_KEY = "voice_assist_start_from_key"

        /** Gesture sources that mean "the user long-pressed the navigation gesture line". */
        val NAVIGATION_SOURCES = setOf(
            "long_press_fullscreen_gesture_line",
            "long_press_home_key",
            "two_gesture_long_press",
        )

        /** Null when the intent is not one this app may read. */
        fun from(intent: Intent?): ScreenSearchRequest? {
            val target = intent ?: return null
            return runCatching {
                ScreenSearchRequest(
                    action = target.action,
                    functionKey = target.getStringExtra(FUNCTION_KEY),
                    triggerType = target.getStringExtra(TRIGGER_TYPE_KEY),
                    startFrom = target.getStringExtra(START_FROM_KEY),
                )
            }.getOrNull()
        }
    }

    /** True only for a navigation long-press screen recognition request. */
    fun matchesScreenSearch(): Boolean =
        action == Intent.ACTION_ASSIST &&
            functionKey == SCREEN_RECOGNITION &&
            triggerType == NAV_LONG_PRESS &&
            startFrom in NAVIGATION_SOURCES
}
