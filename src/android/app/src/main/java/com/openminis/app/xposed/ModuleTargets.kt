package com.openminis.app.xposed

/**
 * [T-eta-xposed-entry] Which processes this module may run in, and which packages it knows how to
 * enter.
 *
 * Ported from Eta `core/ModuleConfig.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. Eta keeps the target list in one place because the module is injected
 * into several OEM surfaces at once, and a name typed twice in two files is how a hook silently
 * stops matching after a ROM update.
 *
 * The predicates are the part that runs before anything else: a module that keeps its lifecycle
 * callbacks in a process it does not hook pays for every app start on the device, so only the own
 * package and declared targets stay alive.
 */
object ModuleTargets {

    /**
     * This app's own package, which the module both hooks (to answer its own control surfaces) and
     * launches for the power-key takeover. It must follow the applicationId: on the Xiaomi
     * 24129PN74C the previous value still named the pre-rename package, so the assistant launch
     * opened another install and the assistant-role check compared a package that could never
     * hold the role. Pinned to BuildConfig by a unit test rather than by memory.
     */
    const val OWN_PACKAGE = "llc.slacker.eta"

    const val SYSTEM_SERVER_PROCESS = "system"
    const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    const val GOOGLE_SEARCH_PACKAGE = "com.google.android.googlequicksearchbox"

    /** The system's own contextual search ("circle to search") entry, used by gesture takeovers. */
    const val CONTEXTUAL_SEARCH_ACTION = "android.app.contextualsearch.action.LAUNCH_CONTEXTUAL_SEARCH"
    const val CONTEXTUAL_SEARCH_SERVICE = "contextual_search"
    const val CIRCLE_TO_SEARCH_ENTRYPOINT = 1
    const val BREENO_PACKAGE = "com.heytap.speechassist"
    const val COLOROS_DIRECT_PACKAGE = "com.coloros.colordirectservice"
    const val COLOROS_MEMORY_PACKAGE = "com.oplus.aimemory"
    const val XIAOAI_PACKAGE = "com.miui.voiceassist"
    const val XIAOMI_LAUNCHER_PACKAGE = "com.miui.home"
    const val XIAOMI_GLOBAL_LAUNCHER_PACKAGE = "com.mi.android.globallauncher"

    /** Assistant surfaces whose agent loop this module may take over. */
    val ASSISTANT_PACKAGES = setOf(BREENO_PACKAGE, XIAOAI_PACKAGE)

    val LAUNCHER_PACKAGES = setOf(XIAOMI_LAUNCHER_PACKAGE, XIAOMI_GLOBAL_LAUNCHER_PACKAGE)

    /**
     * [T-eta-xposed-entry] Targets whose surface only exists in the package's own main process: the
     * launchers, SystemUI and the memory app are hooked in `processName == packageName`, while the
     * Google app, the ColorOS direct service, Breeno and XiaoAi are matched with their subprocesses
     * as well - that is upstream's split (Mangi-11/Eta @ c15de97), and installing SystemUI's hooks in
     * one of its subprocesses would patch a surface that is not there.
     */
    val MAIN_PROCESS_TARGETS: Set<String> =
        LAUNCHER_PACKAGES + SYSTEM_UI_PACKAGE + COLOROS_MEMORY_PACKAGE

    /** Whether a group declared for [target] belongs in the process named [processName]. */
    fun installsInProcess(target: String, processName: String?): Boolean {
        val process = processName?.trim().orEmpty()
        if (process.isEmpty()) return false
        return if (target in MAIN_PROCESS_TARGETS) process == target else isProcessOf(process, target)
    }

    /** Every package the module is declared for, in the order the scope list states them. */
    val SCOPED_PACKAGES = listOf(
        SYSTEM_SERVER_PROCESS,
        SYSTEM_UI_PACKAGE,
        GOOGLE_SEARCH_PACKAGE,
        COLOROS_DIRECT_PACKAGE,
        BREENO_PACKAGE,
        COLOROS_MEMORY_PACKAGE,
        XIAOAI_PACKAGE,
        XIAOMI_LAUNCHER_PACKAGE,
        XIAOMI_GLOBAL_LAUNCHER_PACKAGE,
    )

    /** True when [processName] is a process of [packageName] (Android appends ":suffix"). */
    fun isProcessOf(processName: String?, packageName: String): Boolean {
        val process = processName?.trim().orEmpty()
        if (process.isEmpty() || packageName.isEmpty()) return false
        return process == packageName || process.startsWith("$packageName:")
    }

    /**
     * Whether the module keeps its lifecycle callbacks in this process at all: its own process, or
     * one of the declared targets. Anything else detaches, so the device pays nothing for a process
     * the module cannot touch.
     */
    fun shouldKeepLifecycleCallbacks(processName: String?, ownPackage: String = OWN_PACKAGE): Boolean {
        val process = processName?.trim().orEmpty()
        if (process.isEmpty()) return false
        if (isProcessOf(process, ownPackage)) return true
        return SCOPED_PACKAGES.any { target -> installsInProcess(target, process) }
    }
}
