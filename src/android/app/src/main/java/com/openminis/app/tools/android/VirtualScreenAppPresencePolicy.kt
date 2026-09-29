package com.openminis.app.tools.android

import com.openminis.app.tools.android.vscreen.VirtualScreenPolicy

internal object VirtualScreenAppPresencePolicy {
    fun denialCode(packageName: String?, hasWindowOnDisplay: Boolean): String? =
        if (!packageName.isNullOrBlank() && !hasWindowOnDisplay) VirtualScreenPolicy.APP_LEFT_DISPLAY else null
}
