package com.openminis.app.tools.android.vscreen

import android.content.Context
import android.os.Build

internal data class VirtualScreenProbeSnapshot(
    val passed: Boolean,
    val timestampMs: Long,
    val fingerprint: String,
    val json: String,
)

internal object VirtualScreenProbeCachePolicy {
    fun isCurrentAndPassed(
        passed: Boolean,
        savedFingerprint: String?,
        currentFingerprint: String,
    ): Boolean = passed && !savedFingerprint.isNullOrBlank() && savedFingerprint == currentFingerprint

    fun mayEnable(snapshot: VirtualScreenProbeSnapshot?, currentFingerprint: String): Boolean =
        snapshot != null && isCurrentAndPassed(snapshot.passed, snapshot.fingerprint, currentFingerprint)
}

/** App-process storage only; VScreen state is intentionally not part of Room. */
internal class VirtualScreenPreferences(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(currentFingerprint: String = currentDeviceFingerprint()): Boolean {
        if (!preferences.getBoolean(KEY_ENABLED, false)) return false
        if (hasCurrentPassingProbe(currentFingerprint)) return true
        // A stale or failed probe must not silently re-enable after an OS rollback or a later probe.
        preferences.edit().putBoolean(KEY_ENABLED, false).apply()
        return false
    }

    fun setEnabled(enabled: Boolean, currentFingerprint: String = currentDeviceFingerprint()) {
        if (enabled && !hasCurrentPassingProbe(currentFingerprint)) {
            throw VirtualScreenProbeFailure("vscreen_probe_required", "A current passing device probe is required")
        }
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun saveProbe(report: VirtualScreenProbeReport, fingerprint: String) {
        preferences.edit()
            .putBoolean(KEY_PASSED, report.passed)
            .putLong(KEY_TIMESTAMP, report.timestampMs)
            .putString(KEY_FINGERPRINT, fingerprint)
            .putString(KEY_JSON, report.toJson())
            .apply()
    }

    fun lastProbe(): VirtualScreenProbeSnapshot? {
        val json = preferences.getString(KEY_JSON, null) ?: return null
        val fingerprint = preferences.getString(KEY_FINGERPRINT, null) ?: return null
        return VirtualScreenProbeSnapshot(
            passed = preferences.getBoolean(KEY_PASSED, false),
            timestampMs = preferences.getLong(KEY_TIMESTAMP, 0L),
            fingerprint = fingerprint,
            json = json,
        )
    }

    fun displaySettings(): VirtualScreenDisplaySettings = VirtualScreenDisplaySettings(
        width = preferences.getInt(KEY_WIDTH, VirtualScreenDisplaySettings.DEFAULT_WIDTH),
        height = preferences.getInt(KEY_HEIGHT, VirtualScreenDisplaySettings.DEFAULT_HEIGHT),
        dpi = preferences.getInt(KEY_DPI, VirtualScreenDisplaySettings.DEFAULT_DPI),
    ).takeIf(VirtualScreenDisplaySettingsPolicy::isValid) ?: VirtualScreenDisplaySettings()

    fun setDisplaySettings(settings: VirtualScreenDisplaySettings) {
        require(VirtualScreenDisplaySettingsPolicy.isValid(settings)) { "invalid_vscreen_display_settings" }
        preferences.edit()
            .putInt(KEY_WIDTH, settings.width)
            .putInt(KEY_HEIGHT, settings.height)
            .putInt(KEY_DPI, settings.dpi)
            .apply()
    }

    fun hasCurrentPassingProbe(currentFingerprint: String = currentDeviceFingerprint()): Boolean =
        VirtualScreenProbeCachePolicy.mayEnable(lastProbe(), currentFingerprint)

    companion object {
        private const val PREFS = "vscreen_capability"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PASSED = "probe_passed"
        private const val KEY_TIMESTAMP = "probe_timestamp_ms"
        private const val KEY_FINGERPRINT = "probe_fingerprint"
        private const val KEY_JSON = "probe_json"
        private const val KEY_WIDTH = "display_width"
        private const val KEY_HEIGHT = "display_height"
        private const val KEY_DPI = "display_dpi"

        /**
         * Which privileged host ran the probe. A probe passed under an earlier host (the Shizuku
         * UserService, before 2026-10) says nothing about the current one, so a new host makes every
         * saved probe stale and the feature waits for a fresh pass.
         */
        internal const val SERVICE_HOST = "libsu-root-1"

        fun currentDeviceFingerprint(): String = listOf(
            Build.FINGERPRINT.orEmpty(),
            Build.VERSION.SDK_INT.toString(),
            Build.MANUFACTURER.orEmpty(),
            Build.DEVICE.orEmpty(),
            SERVICE_HOST,
        ).joinToString("|")
    }
}
