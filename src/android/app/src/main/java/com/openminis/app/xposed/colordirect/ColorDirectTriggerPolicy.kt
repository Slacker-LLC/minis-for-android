package com.openminis.app.xposed.colordirect

import org.json.JSONObject

/**
 * [T-eta-xposed-groups] What counts as ColorOS's double-finger screen recognition, and how often it
 * may be turned into a circle-to-search.
 *
 * Ported from Eta `hook/colordirect/ColorDirectHooks.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. Both halves are kept as values because both are the kind of rule that is
 * invisible when it is wrong: ColorOS reports every direct-service gesture through one activity, so
 * claiming the wrong one would hijack a feature the user did not ask about, and the dedup window is
 * what keeps one gesture from becoming two searches.
 */
object ColorDirectTriggerPolicy {

    /** The gesture's own report says this many fingers. */
    const val DOUBLE_FINGER_COUNT = 2

    /** Two reports closer together than this are one gesture. */
    const val DEDUP_WINDOW_MS = 1_000L

    private const val FINGER_TRIGGER = "fingerTrigger"
    private const val TOUCH_INFO = "touchInfo"
    private const val FINGER_COUNT = "fingerCount"

    /**
     * The direct-service payload is a JSON string; anything that does not parse, is not a finger
     * trigger, carries no touch info or reports a finger count other than two is not this gesture.
     */
    fun isDoubleFingerCollect(directExt: String?): Boolean {
        if (directExt.isNullOrBlank()) return false
        return runCatching {
            val json = JSONObject(directExt)
            json.optBoolean(FINGER_TRIGGER, false) &&
                json.optJSONObject(TOUCH_INFO)?.optInt(FINGER_COUNT, 0) == DOUBLE_FINGER_COUNT
        }.getOrDefault(false)
    }

    /** True while the previous takeover is recent enough to swallow this report. */
    fun withinDedupWindow(nowMs: Long, lastHandledMs: Long): Boolean =
        lastHandledMs != 0L && nowMs - lastHandledMs <= DEDUP_WINDOW_MS
}
