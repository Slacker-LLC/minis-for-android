package com.openminis.app.xposed.system

/**
 * [T-eta-xposed-groups] The facts about ColorOS's own power-key assist message.
 *
 * Ported from Eta `hook/system/PowerHooks.kt` and the constants it reads in `core/ModuleConfig.kt`
 * (Mangi-11/Eta @ c15de97); attribution in THIRD_PARTY_LICENSES.md. The message id is the whole
 * trigger - the same handler carries every speech message - and the haptic is the one the ROM's own
 * assistant long press uses, replayed so the gesture still feels like the one the device ships. Both
 * are values so a wrong one is a failing test rather than a gesture that quietly does nothing.
 */
object PowerKeyPolicy {

    /** The `what` of the assist message in PhoneWindowManagerExtImpl's speech handler. */
    const val ASSIST_MESSAGE_WHAT = 0x3F3

    /** The haptic effect and reason the ROM's own assistant long press uses. */
    const val OEM_ASSISTANT_HAPTIC_EFFECT_ID = 0
    const val OEM_ASSISTANT_HAPTIC_REASON = "Speech - Long Press"

    /** Two reports closer together than this are one press. */
    const val DEDUP_WINDOW_MS = 1_000L

    /** Only this message is the power-key assist; everything else in the handler is not ours. */
    fun isAssistMessage(what: Int): Boolean = what == ASSIST_MESSAGE_WHAT

    /** True while the previous launch is recent enough to swallow this press. */
    fun withinDedupWindow(nowMs: Long, lastLaunchMs: Long): Boolean =
        lastLaunchMs != 0L && nowMs - lastLaunchMs <= DEDUP_WINDOW_MS
}
