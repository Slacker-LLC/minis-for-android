package com.openminis.app.integrity

import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Every refusal by the device-integrity protection, newest first, for the diagnostics list in
 * Settings. Each one is also written to the app log, which survives a restart; this list does not.
 */
object IntegrityAudit {
    private const val MAX_ENTRIES = 200
    private const val TAG = "DeviceIntegrity"

    data class Entry(
        val timeMs: Long,
        val entryPoint: String,
        val sessionId: String?,
        val category: String,
        val target: String,
    )

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun record(entryPoint: String, sessionId: String?, denial: DeviceIntegrityPolicy.Denial, nowMs: Long = System.currentTimeMillis()) {
        val entry = Entry(nowMs, entryPoint, sessionId, denial.category.label, denial.target.take(200))
        _entries.value = (listOf(entry) + _entries.value).take(MAX_ENTRIES)
        // AppLogger is a no-op before init and on the host; the in-memory list is the record then.
        runCatching {
            AppLogger.warning(TAG, "blocked category=${entry.category} entry=$entryPoint session=${sessionId ?: "-"} target=${entry.target}")
        }
    }

    internal fun clearForTest() {
        _entries.value = emptyList()
    }
}
