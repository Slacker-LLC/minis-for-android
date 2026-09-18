package com.openminis.app.xposed.system

import com.openminis.app.xposed.ModuleTargets

/**
 * [T-eta-xposed-groups] Which callers may start the system's contextual search without holding the
 * signature permission.
 *
 * Ported from Eta `hook/system/ContextualSearchCallerPolicy.kt` (Mangi-11/Eta @ c15de97);
 * attribution in THIRD_PARTY_LICENSES.md. The point is the narrowness: the surface that owns the
 * gesture is let through, and everything else keeps the platform's own permission check. On the
 * HyperOS builds this module exists for, the gesture belongs to the launcher and to the voice
 * assistant, so those are accepted only when the user turned the gesture takeover on and only when
 * the package is really part of the system image.
 */
object ContextualSearchCallerPolicy {

    /** SystemUI owns the navigation bar; ColorOS routes its own gesture through its service. */
    val PRIVILEGED_CALLERS: Set<String> = setOf(
        ModuleTargets.SYSTEM_UI_PACKAGE,
        ModuleTargets.COLOROS_DIRECT_PACKAGE,
    )

    /** The HyperOS surfaces a circle-to-search gesture can arrive from. */
    val HYPEROS_CALLERS: Set<String> = ModuleTargets.LAUNCHER_PACKAGES + ModuleTargets.XIAOAI_PACKAGE

    /**
     * [callingPackages] is every package behind the calling UID, because that shared UID is the
     * actual permission boundary; [systemPackages] is the subset of them that the platform ships.
     */
    fun allows(
        callingPackages: List<String>,
        systemPackages: Set<String>,
        gestureEnabled: Boolean,
    ): Boolean = callingPackages.any { packageName ->
        packageName in PRIVILEGED_CALLERS ||
            (gestureEnabled && packageName in HYPEROS_CALLERS && packageName in systemPackages)
    }
}
