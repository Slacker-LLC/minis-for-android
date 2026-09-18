package com.openminis.app.xposed.hyperos

import android.content.Context
import com.openminis.app.xposed.HookLogger
import com.openminis.app.xposed.ModulePrefs
import com.openminis.app.xposed.ModuleTargets
import com.openminis.app.xposed.safeLogType
import com.openminis.app.xposed.system.CircleToSearchInvoker

/**
 * [T-eta-xposed-groups] Handing a HyperOS gesture to the system's own contextual search.
 *
 * Ported from Eta `hook/hyperos/HyperOsSearchTrigger.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. The launcher and the gesture bar both go through here, so "the search is
 * available and was started" is decided once: the switch is the same gesture-bar switch the other
 * HyperOS group reads, a missing context or a missing search entry keeps the ROM's behaviour, and no
 * failure is ever allowed to escape into the launcher's input path.
 */
object HyperOsSearchTrigger {

    private const val SOURCE = "HyperOS"

    fun isEnabled(): Boolean =
        ModulePrefs.isEnabled(ModulePrefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH)

    fun trigger(context: Context?, logger: HookLogger): Boolean {
        if (!isEnabled()) return false
        val usableContext = context ?: run {
            logger.warnThrottled("hyperos_search_context_missing") {
                "HyperOS: the search entry point has no context, keeping the system behaviour"
            }
            return false
        }
        return try {
            CircleToSearchInvoker.isAvailable(
                usableContext,
                logger,
                SOURCE,
                "keeping the system behaviour",
            ) &&
                CircleToSearchInvoker.trigger(
                    logger,
                    SOURCE,
                    entryPoint = ModuleTargets.CIRCLE_TO_SEARCH_ENTRYPOINT,
                )
        } catch (exception: Exception) {
            logger.warnThrottled("hyperos_search_failed") {
                "HyperOS: triggering the search failed (${exception.safeLogType()})"
            }
            false
        }
    }
}
