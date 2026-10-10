package com.openminis.app.integrity

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether device-integrity protection (Issue #182) is on. It is on by default and stays on until the
 * user turns it off in Settings → Permissions.
 *
 * Like `AutonomyStore`, nothing else can write it: there is no config field for it, the backup
 * allowlist does not name its preference file, and the agent has no tool that reaches it. Until
 * [init] has run the answer is "on".
 */
object DeviceProtectionStore {
    private const val PREFS = "device_protection_prefs"
    private const val KEY = "enabled"
    private var prefs: SharedPreferences? = null

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    val isEnabled: Boolean get() = _enabled.value

    /** Call once early, from MinisApp.onCreate. Idempotent. */
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _enabled.value = p.getBoolean(KEY, true)
    }

    fun setEnabled(value: Boolean) {
        prefs?.edit { putBoolean(KEY, value) }
        _enabled.value = value
    }
}
