package com.openminis.app.xposed

import com.openminis.app.xposed.google.GoogleEligibilityHooks
import com.openminis.app.xposed.google.GoogleVoiceCommandHooks
import com.openminis.app.xposed.hyperos.HyperOsScreenSearchHooks
import com.openminis.app.xposed.hyperos.HyperOsPowerHooks
import com.openminis.app.xposed.system.ContextualSearchHooks

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
        // Google's own floaty assistant surface, in the Google app's process.
        HookGroupRegistry.register(ModuleTargets.GOOGLE_SEARCH_PACKAGE) { module, classLoader, log ->
            GoogleVoiceCommandHooks.install(module, classLoader, log)
        }
        // The same process also answers Google's eligibility questions: without those answers its
        // screen features stay refused no matter what the gestures do.
        HookGroupRegistry.register(ModuleTargets.GOOGLE_SEARCH_PACKAGE) { module, classLoader, log ->
            GoogleEligibilityHooks.install(module, classLoader, log)
        }
    }
}
