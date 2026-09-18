package com.openminis.app.xposed.google

import com.openminis.app.xposed.ModulePrefs
import java.util.WeakHashMap

/**
 * [T-eta-xposed-groups] Which switch governs a Gemini floaty voice command, and whether the one
 * already queued is still wanted.
 *
 * Ported from Eta `hook/google/GoogleAppHooks.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. Kept as values so the two decisions a delayed send makes - which switch
 * gates it, and whether the lock-screen state that queued it still holds - are testable without an
 * Android runtime.
 */
object GoogleVoiceCommandPolicy {

    /**
     * Lock screen and screen-on are separate switches because they are separate user choices: the
     * first is about answering an assistant over a locked device, the second about the ordinary
     * unlocked path, and wanting one says nothing about the other.
     */
    fun prefKey(keyguardLocked: Boolean): String =
        if (keyguardLocked) {
            ModulePrefs.Keys.LOCKSCREEN_VOICE_COMMAND
        } else {
            ModulePrefs.Keys.SCREEN_ON_VOICE_COMMAND
        }

    /** The word the log line uses, so one line says which case fired. */
    fun scenario(keyguardLocked: Boolean): String = if (keyguardLocked) "lockscreen" else "screen-on"

    /**
     * The queued command is only sent while the lock-screen state has not flipped in between: a
     * voice command queued for the lock screen must not open the assistant over an unlocked
     * session, and the other way round.
     */
    fun stillEligible(queuedWhileLocked: Boolean, currentlyLocked: Boolean): Boolean =
        queuedWhileLocked == currentlyLocked
}

/**
 * [T-eta-xposed-groups] One voice command per floaty instance per window.
 *
 * Ported from Eta `hook/google/GoogleAppHooks.kt` (Mangi-11/Eta @ c15de97). The floaty activity is
 * a singleton that resumes repeatedly, so without a gate every resume would send another command;
 * the Android side keys this by the Activity instance in a [WeakHashMap], so a closed floaty does
 * not leak, and the clock is injected so the window is testable.
 */
class VoiceCommandGate(
    private val windowMs: Long,
    private val clock: () -> Long,
    private val marks: MutableMap<Any, Long> = WeakHashMap(),
) {

    /** True when this key may send now; a refused attempt leaves the previous mark in place. */
    @Synchronized
    fun shouldSend(key: Any): Boolean {
        val now = clock()
        val previous = marks[key]
        if (previous != null && now - previous < windowMs) return false
        marks[key] = now
        return true
    }

    /**
     * Called when the queued command did not happen, so the next resume may try again: a launch
     * that failed must not eat the window it was supposed to use.
     */
    @Synchronized
    fun clear(key: Any) {
        marks.remove(key)
    }
}
