package com.openminis.app.ui.chat

import com.openminis.app.data.ContextPolicy

/**
 * What the mid-loop context guard does for a given reading, separated from the side effects
 * ([ChatViewModel] compacts, logs and posts the notice). Moved out of the ViewModel unchanged.
 */
internal object InLoopContextPolicy {
    /**
     * [T-android-auto-compact-inloop] Max in-loop compactions per runAgentLoop.
     * Bounds compact-thrash within a single turn; the MAX_AGENT_TURNS ceiling is
     * never reset by compaction, so this is a second, tighter backstop.
     */
    const val MAX_COMPACTIONS = 3

    enum class Decision {
        /** Under threshold, or nothing known yet — issue the next API call as normal. */
        PROCEED,

        /** Over the compact threshold with budget left — compact, then re-run the iteration. */
        COMPACT,

        /** Still over the threshold after [MAX_COMPACTIONS] compactions. */
        STOP_COMPACTION_BUDGET,

        /**
         * EXHAUSTED is only ever returned by `exhaustedOnly` tiers — windows under 64K, where
         * ContextPolicy sets compactThreshold = 0 precisely BECAUSE the window is too small for
         * auto-compact to pay for itself (the summary plus re-appended recent turns would eat the
         * headroom it just freed). A "rescue" compaction would contradict the policy, so stop and let
         * the user decide.
         */
        STOP_EXHAUSTED,
    }

    /** [tokens] is the last reported context size (<= 0 = unknown); [window] the live context window, if known. */
    fun decide(tokens: Int, window: Int?, compactionsSoFar: Int): Decision {
        if (tokens <= 0) return Decision.PROCEED
        if (window == null) return Decision.PROCEED
        return when (ContextPolicy.forContextWindow(window).check(tokens, window)) {
            ContextPolicy.CheckResult.OK -> Decision.PROCEED
            ContextPolicy.CheckResult.NEEDS_COMPACT ->
                if (compactionsSoFar >= MAX_COMPACTIONS) Decision.STOP_COMPACTION_BUDGET else Decision.COMPACT
            ContextPolicy.CheckResult.EXHAUSTED -> Decision.STOP_EXHAUSTED
        }
    }
}
