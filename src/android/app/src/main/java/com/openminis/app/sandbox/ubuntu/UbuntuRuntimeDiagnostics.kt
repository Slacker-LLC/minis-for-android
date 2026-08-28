package com.openminis.app.sandbox.ubuntu

/**
 * Pure diagnostic bridge for callers that gate Ubuntu availability without
 * depending on the runtime singleton itself. [UbuntuRuntime] publishes the
 * latest startup/RPC failure here; a successful status clears it.
 */
object UbuntuRuntimeDiagnostics {
    @Volatile
    var lastError: String? = null
        private set

    internal fun update(error: String?) {
        lastError = error?.trim()?.takeIf { it.isNotEmpty() }
    }
}
