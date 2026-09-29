package com.openminis.app.tools.android

import android.content.Context

/** Physical display access is denied for unattended sessions unless the user explicitly opts in. */
object UnattendedScreenAccessPolicy {
    const val PHYSICAL_DISPLAY = 0
    const val ERROR_UNATTENDED_PHYSICAL_SCREEN_DENIED = "physical_screen_denied_unattended"

    fun denialCode(displayId: Int, unattended: Boolean, allowUnattendedPhysical: Boolean): String? =
        if (displayId == PHYSICAL_DISPLAY && unattended && !allowUnattendedPhysical) {
            ERROR_UNATTENDED_PHYSICAL_SCREEN_DENIED
        } else null
}

/** User choice is app-local and deliberately not part of Room or synced data. */
object UnattendedAccessPrefs {
    private const val PREFS = "unattended_access"
    private const val KEY_ALLOW_PHYSICAL_SCREEN = "allow_physical_screen"

    fun allowsPhysicalScreen(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getBoolean(KEY_ALLOW_PHYSICAL_SCREEN, false)

    fun setAllowPhysicalScreen(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ALLOW_PHYSICAL_SCREEN, enabled).apply()
    }
}
