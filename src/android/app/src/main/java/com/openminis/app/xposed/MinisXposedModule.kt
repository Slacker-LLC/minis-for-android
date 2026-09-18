package com.openminis.app.xposed

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.ModuleLoadedParam
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam

/**
 * [T-eta-xposed-entry] The module LSPosed loads, declared in `META-INF/xposed/java_init.list`.
 *
 * Ported from Eta `ModuleMain.kt` (Mangi-11/Eta @ c15de97); attribution in THIRD_PARTY_LICENSES.md.
 * Eta's lifecycle is kept: the process it was loaded into is recorded first, callbacks are dropped
 * with `detach()` in any process the module has no business in, system_server is dispatched through
 * its own entry point, and a package target installs only when the current process actually belongs
 * to that package — a hook installed from the wrong process would silently patch nothing.
 *
 * The dispatch itself asks [HookGroupRegistry] instead of carrying a `when` per vendor, because this
 * app grows its vendor support one slice at a time. With the registry still empty, the module loads,
 * stays out of the way everywhere it does not belong, and says in the log that it has nothing to
 * install yet — which is the honest state of this feature today.
 */
class MinisXposedModule : XposedModule() {

    private var currentProcessName: String? = null

    override fun onModuleLoaded(param: ModuleLoadedParam) {
        currentProcessName = param.processName
        if (!param.isSystemServer && !ModuleTargets.shouldKeepLifecycleCallbacks(param.processName)) {
            detach()
            return
        }
        log(
            Log.INFO,
            TAG,
            "module loaded process=${param.processName} systemServer=${param.isSystemServer} " +
                "framework=$frameworkName($frameworkVersionCode) api=$apiVersion",
        )
    }

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        installFor(HookGroupRegistry.SYSTEM_TARGET, param.classLoader)
    }

    override fun onPackageReady(param: PackageReadyParam) {
        // A group installs only in the target's own process: patching another app's classes from
        // here would either do nothing or touch the wrong process's copy.
        if (!ModuleTargets.isProcessOf(currentProcessName, param.packageName)) return
        installFor(param.packageName, param.classLoader)
    }

    private fun installFor(target: String, classLoader: ClassLoader) {
        val installers = HookGroupRegistry.forTarget(target)
        if (installers.isEmpty()) {
            log(Log.INFO, TAG, "target=$target has no hook group yet; nothing was installed")
            return
        }
        val reports = installers.map { installer ->
            val journal = HookInstallJournal(target)
            var report: HookInstallReport? = null
            journal.capture(
                block = {
                    report = installer.install(this, classLoader) { line -> log(Log.INFO, TAG, line) }
                },
                onFailure = { failure ->
                    log(
                        Log.ERROR,
                        TAG,
                        "hook group $target failed: ${failure.javaClass.simpleName}: ${failure.message}",
                    )
                },
            )
            report ?: journal.report()
        }
        log(Log.INFO, TAG, HookInstallReport.combine(target, reports).summary())
    }

    companion object {
        /** Tag the module logs under; short because it appears in every logcat line. */
        const val TAG = "Minis"
    }
}
