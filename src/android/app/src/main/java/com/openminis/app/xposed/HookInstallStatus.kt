package com.openminis.app.xposed

/**
 * [T-eta-xposed-entry] How a hook group ended up: the ledger a module reports through, with no
 * framework objects in it.
 *
 * Ported from Eta `core/HookRegistrar.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. Eta classifies every hook by one of four states and logs the counts,
 * which is what makes a vendor ROM integration debuggable: "the module is loaded" and "the hook
 * took" are different facts, and a MISSING target on an unknown ROM version must be visible
 * instead of silently ignored.
 */
enum class HookInstallStatus { INSTALLED, MISSING, FAILED, SKIPPED }

data class HookInstallEntry(
    val group: String,
    val id: String,
    val description: String,
    val status: HookInstallStatus,
    val detail: String? = null,
)

data class HookInstallReport(val group: String, val entries: List<HookInstallEntry>) {

    val installedCount: Int get() = entries.count { it.status == HookInstallStatus.INSTALLED }
    val missingCount: Int get() = entries.count { it.status == HookInstallStatus.MISSING }
    val failedCount: Int get() = entries.count { it.status == HookInstallStatus.FAILED }
    val skippedCount: Int get() = entries.count { it.status == HookInstallStatus.SKIPPED }

    fun summary(): String =
        "hook install: installed=$installedCount, missing=$missingCount, " +
            "failed=$failedCount, skipped=$skippedCount (group=$group)"

    companion object {
        fun combine(group: String, reports: Iterable<HookInstallReport>): HookInstallReport =
            HookInstallReport(group = group, entries = reports.flatMap { it.entries })
    }
}

/**
 * The ledger one group writes into. [capture] turns an exception during installation into a FAILED
 * entry before rethrowing, so a half-installed group cannot report success.
 */
class HookInstallJournal(private val group: String) {
    private val entries = mutableListOf<HookInstallEntry>()

    fun capture(block: () -> Unit, onFailure: (Exception) -> Unit = {}) {
        try {
            block()
        } catch (exception: Exception) {
            failed(
                id = "install.failed",
                description = "$group installation",
                detail = exception.javaClass.simpleName,
            )
            onFailure(exception)
        }
    }

    fun installed(id: String, description: String) = record(id, description, HookInstallStatus.INSTALLED)

    fun missing(id: String, description: String, detail: String) =
        record(id, description, HookInstallStatus.MISSING, detail)

    fun failed(id: String, description: String, detail: String) =
        record(id, description, HookInstallStatus.FAILED, detail)

    fun skipped(id: String, description: String, detail: String) =
        record(id, description, HookInstallStatus.SKIPPED, detail)

    fun report(): HookInstallReport = HookInstallReport(group, entries.toList())

    private fun record(
        id: String,
        description: String,
        status: HookInstallStatus,
        detail: String? = null,
    ) {
        entries += HookInstallEntry(group, id, description, status, detail)
    }
}
