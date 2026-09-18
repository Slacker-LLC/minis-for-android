package com.openminis.app.xposed

import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable

/** [T-eta-xposed-entry] What one installed group hands back: how it went, and the handles to undo it. */
data class HookInstallation(
    val report: HookInstallReport,
    val handles: List<XposedInterface.HookHandle>,
) {
    companion object {
        fun combine(group: String, installations: Iterable<HookInstallation>): HookInstallation {
            val list = installations.toList()
            return HookInstallation(
                report = HookInstallReport.combine(group, list.map { it.report }),
                handles = list.flatMap { it.handles },
            )
        }
    }
}

/**
 * [T-eta-xposed-entry] One feature area's registration surface.
 *
 * Ported from Eta `core/HookRegistrar.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. The registrar only registers and reports — finding the target stays the
 * group's job — and every path writes into the ledger: a hook that installed is INSTALLED, a target
 * this ROM does not have is MISSING, a throw during registration is FAILED, and a duplicate
 * registration is refused instead of stacking two hooks on one method. `install` catches and reports a
 * group-level failure but keeps whatever was already registered, because a partially useful group is
 * better than none and the report says which part is missing.
 */
class HookRegistrar(
    private val module: XposedModule,
    logger: HookLogger,
    private val group: String,
) {
    val logger: HookLogger = logger.scoped(group)

    private val journal = HookInstallJournal(group)
    private val handles = mutableListOf<XposedInterface.HookHandle>()
    private val registrationKeys = mutableSetOf<RegistrationKey>()

    fun install(block: HookRegistrar.() -> Unit): HookInstallation {
        journal.capture(block = { block() }) { exception ->
            logger.error("hook group install failed: ${exception.javaClass.simpleName}: ${exception.message}")
        }
        return finish()
    }

    fun intercept(
        id: String,
        executable: Executable,
        description: String,
        priority: Int = XposedInterface.PRIORITY_DEFAULT,
        hooker: (XposedInterface.Chain) -> Any?,
    ): XposedInterface.HookHandle? {
        require(STABLE_ID.matches(id)) { "hook id is invalid: $id" }
        val fullId = "minis.$id"
        val registrationKey = RegistrationKey(executable, fullId)
        if (!registrationKeys.add(registrationKey)) {
            val detail = "duplicate hook registration: $description ($fullId)"
            journal.failed(id, description, detail)
            logger.error(detail)
            return null
        }
        return try {
            val handle = module.hook(executable)
                .setPriority(priority)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId(fullId)
                .intercept { chain -> hooker(chain) }
            handles += handle
            journal.installed(id, description)
            logger.debug { "installed hook: $description" }
            handle
        } catch (exception: Exception) {
            // A framework-level failure arrives as an Error and must keep propagating.
            registrationKeys.remove(registrationKey)
            journal.failed(id, description, exception.javaClass.simpleName)
            logger.error("hook install failed: $description (${exception.javaClass.simpleName})")
            null
        }
    }

    fun missing(id: String, description: String, detail: String) {
        require(STABLE_ID.matches(id)) { "hook id is invalid: $id" }
        journal.missing(id, description, detail)
        logger.warn(detail)
    }

    fun skipped(id: String, description: String, detail: String) {
        require(STABLE_ID.matches(id)) { "hook id is invalid: $id" }
        journal.skipped(id, description, detail)
        logger.debug { detail }
    }

    private fun finish(): HookInstallation =
        HookInstallation(report = journal.report(), handles = handles.toList())

    private data class RegistrationKey(val executable: Executable, val fullId: String)

    companion object {
        /** Ids are lowercase, dotted where they nest, and stable across releases. */
        val STABLE_ID = Regex("[a-z0-9][a-z0-9._-]*")
    }
}
