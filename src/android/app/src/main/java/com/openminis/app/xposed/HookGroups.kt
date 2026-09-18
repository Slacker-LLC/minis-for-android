package com.openminis.app.xposed

import com.openminis.app.xposed.aimemory.ColorOsMemoryHooks
import com.openminis.app.xposed.colordirect.ColorDirectHooks
import com.openminis.app.xposed.google.GoogleEligibilityHooks
import com.openminis.app.xposed.hyperos.HyperOsScreenSearchHooks
import com.openminis.app.xposed.hyperos.HyperOsPowerHooks
import com.openminis.app.xposed.hyperos.HyperOsLauncherHooks
import com.openminis.app.xposed.system.AccessibilityProtectionHooks
import com.openminis.app.xposed.system.ContextualSearchHooks
import com.openminis.app.xposed.system.HotwordSelfHealHooks

/**
 * [T-eta-xposed-groups] The groups this module knows, wired to the targets they belong to.
 *
 * Eta imports its groups directly from `ModuleMain`; this app registers them here so the entry
 * class stays about the framework and this file stays about the product. Every group added later
 * gets one line, and the ledger reports per group whether this device actually has the target.
 */
object HookGroups {

    @Volatile
    private var registered = false

    /** Idempotent: the framework can load the module more than once per process lifetime. */
    @Synchronized
    fun registerDefaults() {
        if (registered) return
        registered = true
        HookGroupRegistry.register(ModuleTargets.XIAOAI_PACKAGE) { module, classLoader, log ->
            HyperOsScreenSearchHooks.install(module, classLoader, log)
        }
        // The power-key dispatcher lives in system_server.
        HookGroupRegistry.register(HookGroupRegistry.SYSTEM_TARGET) { module, classLoader, log ->
            HyperOsPowerHooks.install(module, classLoader, log)
        }
        // So does the system's own contextual search, which the gesture takeovers hand the user's
        // gesture to when the ROM never brought it up.
        HookGroupRegistry.register(HookGroupRegistry.SYSTEM_TARGET) { module, classLoader, log ->
            ContextualSearchHooks.install(module, classLoader, log)
        }
        // And the accessibility protection, which keeps this app's own service enabled: every GUI
        // tool in this app depends on it.
        HookGroupRegistry.register(HookGroupRegistry.SYSTEM_TARGET) { module, classLoader, log ->
            AccessibilityProtectionHooks.install(module, classLoader, log)
        }
        // And the assistant's hotword detection, which some builds drop when the screen goes off.
        HookGroupRegistry.register(HookGroupRegistry.SYSTEM_TARGET) { module, classLoader, log ->
            HotwordSelfHealHooks.install(module, classLoader, log)
        }
        // The launcher's own long press on the navigation bar, in both launcher packages.
        ModuleTargets.LAUNCHER_PACKAGES.forEach { launcher ->
            HookGroupRegistry.register(launcher) { module, classLoader, log ->
                HyperOsLauncherHooks.install(module, classLoader, log)
            }
        }
        // Google's own process answers the eligibility questions: without those answers its screen
        // features stay refused no matter what the gestures do.
        HookGroupRegistry.register(ModuleTargets.GOOGLE_SEARCH_PACKAGE) { module, classLoader, log ->
            GoogleEligibilityHooks.install(module, classLoader, log)
        }
        // The ColorOS memory app is asked through its own provider, from inside its own process.
        HookGroupRegistry.register(ModuleTargets.COLOROS_MEMORY_PACKAGE) { module, classLoader, log ->
            ColorOsMemoryHooks.install(module, classLoader, log)
        }
        // ColorOS's direct-service gesture surface: the two-finger recognition it owns.
        HookGroupRegistry.register(ModuleTargets.COLOROS_DIRECT_PACKAGE) { module, classLoader, log ->
            ColorDirectHooks.install(module, classLoader, log)
        }
    }
}
