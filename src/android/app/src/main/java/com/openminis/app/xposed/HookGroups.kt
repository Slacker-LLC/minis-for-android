package com.openminis.app.xposed

import com.openminis.app.xposed.hyperos.HyperOsScreenSearchHooks

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
    }
}
