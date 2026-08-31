package com.openminis.app.tools.android

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** User-owned Root authority level. Agent tool arguments never select it. */
enum class PrivilegedAccessMode(val wireValue: String) {
    STANDARD("standard"),
    FULL_ACCESS("full"),
}

object PrivilegedAccessModeStore {
    private const val PREFS = "privileged_access_mode"
    private const val KEY_MODE = "mode"

    private val current = MutableStateFlow(PrivilegedAccessMode.STANDARD)

    @Volatile
    private var loaded = false

    fun get(context: Context): PrivilegedAccessMode {
        ensureLoaded(context)
        return current.value
    }

    fun observe(context: Context): StateFlow<PrivilegedAccessMode> {
        ensureLoaded(context)
        return current.asStateFlow()
    }

    /** UI-only mutation seam. This function is intentionally not exposed as an Agent tool. */
    fun setFromUserSettings(context: Context, mode: PrivilegedAccessMode) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MODE, mode.wireValue)
            .apply()
        loaded = true
        current.value = mode
    }

    @Synchronized
    private fun ensureLoaded(context: Context) {
        if (loaded) return
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_MODE, null)
        current.value = parse(raw)
        loaded = true
    }

    internal fun parse(raw: String?): PrivilegedAccessMode = when (raw) {
        PrivilegedAccessMode.FULL_ACCESS.wireValue -> PrivilegedAccessMode.FULL_ACCESS
        else -> PrivilegedAccessMode.STANDARD
    }
}
