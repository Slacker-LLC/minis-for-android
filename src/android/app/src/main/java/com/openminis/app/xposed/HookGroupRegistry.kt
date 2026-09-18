package com.openminis.app.xposed

/**
 * [T-eta-xposed-entry] Installs one feature area's hooks and reports what happened.
 *
 * Ported from the shape of Eta's per-domain installers (its `hook` package's per-domain
 * `ModuleMain`) (Mangi-11/Eta @ c15de97); attribution in THIRD_PARTY_LICENSES.md. Eta passes the
 * module, a logger and the target class loader to each group and takes back a report; the same
 * contract here means the module entry never grows knowledge of individual vendor surfaces, and a
 * group that lands later does not touch it.
 */
fun interface HookGroupInstaller {
    fun install(module: Any, classLoader: ClassLoader, log: (String) -> Unit): HookInstallReport
}

/**
 * [T-eta-xposed-entry] Which group installs for which target package, in registration order.
 *
 * Eta dispatches by a `when (param.packageName)` in the module entry, which is fine while every
 * branch exists. This app grows its vendor support one slice at a time, so the entry asks the
 * registry instead: an empty registry means the module loads and reports honestly that it has
 * nothing to install yet, rather than pretending a target was handled.
 */
object HookGroupRegistry {

    /** The pseudo-target for hooks that live in system_server. */
    const val SYSTEM_TARGET = "system_server"

    private val installers = linkedMapOf<String, MutableList<HookGroupInstaller>>()

    fun register(target: String, installer: HookGroupInstaller) {
        val key = target.trim()
        require(key.isNotEmpty()) { "hook target must not be blank" }
        installers.getOrPut(key) { mutableListOf() } += installer
    }

    fun forTarget(target: String): List<HookGroupInstaller> =
        installers[target.trim()]?.toList().orEmpty()

    fun targets(): Set<String> = installers.keys.toSet()

    /** Test seam: the registry is process-wide and the module loads once. */
    internal fun clear() = installers.clear()
}
