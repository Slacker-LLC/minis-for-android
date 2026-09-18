package com.openminis.app.xposed.system

import com.openminis.app.xposed.ModuleTargets

/**
 * [T-eta-xposed-groups] When the hotword self-heal runs, and how often it retries.
 *
 * Ported from Eta `hook/system/HotwordSelfHealHooks.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. The numbers and the rules are kept as values so the schedule can be
 * pinned by tests: a self-heal that retries too eagerly is worse than none, because the service it
 * is poking is the one that listens for a wake word.
 */
object HotwordSelfHealPolicy {

    /** Attempts per screen-off, including the first one. */
    const val RETRY_COUNT = 3

    /** First attempt after the screen went off. */
    const val INITIAL_DELAY_MS = 1_200L

    /** Each further attempt after the previous one failed. */
    const val STEP_DELAY_MS = 1_400L

    /** Two screen-offs closer together than this are one event, not two heals. */
    const val SCREEN_OFF_COOLDOWN_MS = 8_000L

    /** Only the default display: a second screen turning off is not this device going to sleep. */
    fun isPrimaryDisplay(displayId: Int): Boolean = displayId == 0

    /** True while the last schedule is recent enough to swallow another one. */
    fun withinCooldown(nowMs: Long, lastScheduleMs: Long): Boolean =
        lastScheduleMs != 0L && nowMs - lastScheduleMs < SCREEN_OFF_COOLDOWN_MS

    /** Whether another attempt is allowed after [attempt] (1-based) failed. */
    fun hasAttemptsLeft(attempt: Int): Boolean = attempt < RETRY_COUNT

    /**
     * The software hotword session only exists for the assistant Google ships; another assistant
     * keeps its own machinery, and its internals are not this module's business.
     */
    fun isGoogleAssistantComponent(packageName: String?): Boolean =
        packageName == ModuleTargets.GOOGLE_SEARCH_PACKAGE
}
