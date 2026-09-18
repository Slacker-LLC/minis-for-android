package com.openminis.app.tools.android

/**
 * [T-eta-wait-for-package] When a foreground observation satisfies a package wait, ported
 * from Eta's `wait_for_package` (`agent/tool/AgentLocalTools.kt`, Mangi-11/Eta @ c15de97 —
 * attribution in THIRD_PARTY_LICENSES.md).
 *
 * Fail-closed rule kept from the app's own window semantics: an unreadable foreground is
 * never a match. A wait that cannot see the screen keeps polling and, if it runs out of
 * time, reports that the visibility was unknown instead of claiming the target never
 * appeared.
 */
object PackageWaitPolicy {

    enum class Mode(val wire: String) {
        APPEAR("appear"),
        DISAPPEAR("disappear"),
        ;

        companion object {
            /** Unknown spellings fall back to APPEAR, matching the text wait. */
            fun parse(raw: String?): Mode =
                entries.firstOrNull { it.wire.equals(raw?.trim(), ignoreCase = true) } ?: APPEAR
        }
    }

    data class Observation(val packageName: String?, val visible: Boolean)

    enum class Decision { MATCHED, PENDING, UNKNOWN }

    fun decide(target: String, mode: Mode, observation: Observation): Decision {
        val observed = observation.packageName?.takeIf { it.isNotEmpty() } ?: return Decision.UNKNOWN
        if (!observation.visible) return Decision.UNKNOWN
        val isTarget = observed.equals(target, ignoreCase = true)
        return when (mode) {
            Mode.APPEAR -> if (isTarget) Decision.MATCHED else Decision.PENDING
            Mode.DISAPPEAR -> if (isTarget) Decision.PENDING else Decision.MATCHED
        }
    }
}
