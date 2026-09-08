package com.openminis.app.tools.android

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Compatibility shim for the old Root Standard / Full Access UI.
 *
 * Upstream OpenMinis has no user-selectable Root permission mode. Keep the
 * enum only so existing runtime call sites do not need to be rewritten, but
 * do not expose or persist a mode choice. Runtime calls use the unrestricted
 * structured minisd path so existing Root/Ubuntu capabilities keep working;
 * UI observers always see the neutral STANDARD value and therefore never
 * surface the removed Full Access state.
 */
enum class PrivilegedAccessMode(val wireValue: String) {
    STANDARD("standard"),
    FULL_ACCESS("full"),
}

object PrivilegedAccessModeStore {
    private const val PREFS = "privileged_access_mode"

    private val current = MutableStateFlow(PrivilegedAccessMode.STANDARD)

    /** Runtime compatibility: no App-owned Root approval mode remains. */
    fun get(context: Context): PrivilegedAccessMode {
        clearLegacyPreference(context)
        return PrivilegedAccessMode.FULL_ACCESS
    }

    /** UI compatibility: there is no selectable Full Access state anymore. */
    fun observe(context: Context): StateFlow<PrivilegedAccessMode> {
        clearLegacyPreference(context)
        current.value = PrivilegedAccessMode.STANDARD
        return current.asStateFlow()
    }

    /** Kept for old call sites; mode changes are intentionally ignored. */
    fun setFromUserSettings(context: Context, mode: PrivilegedAccessMode) {
        clearLegacyPreference(context)
        current.value = PrivilegedAccessMode.STANDARD
    }

    private fun clearLegacyPreference(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
    }

    /** Legacy parser retained for source/test compatibility; no runtime path uses it. */
    internal fun parse(raw: String?): PrivilegedAccessMode = when (raw) {
        PrivilegedAccessMode.FULL_ACCESS.wireValue -> PrivilegedAccessMode.FULL_ACCESS
        else -> PrivilegedAccessMode.STANDARD
    }
}
