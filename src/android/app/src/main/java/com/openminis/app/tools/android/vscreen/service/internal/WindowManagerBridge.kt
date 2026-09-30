/*
 * Adapted for Minis from ShadowAuto (`android-notes/ShadowAuto`, commit `611e5eb0e1c94befda3c17c97438912dfd30e8a8`); modified.
 * Original source: android-shell/src/main/java/com/silentauto/shell/WindowManagerBridge.java.
 * Licensed under Apache-2.0; see third_party/shadowauto/LICENSE.
 */
package com.openminis.app.tools.android.vscreen.service.internal

import android.os.Build
import android.os.IBinder
import android.os.IInterface

internal class WindowManagerBridge {
    fun getDisplayImePolicy(displayId: Int): Int = runCatching {
        val method = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.javaClass.getMethod("getDisplayImePolicy", Int::class.javaPrimitiveType)
        } else {
            manager.javaClass.getMethod("shouldShowIme", Int::class.javaPrimitiveType)
        }
        val result = method.invoke(manager, displayId)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) result as Int
        else if (result as Boolean) DISPLAY_IME_POLICY_LOCAL else DISPLAY_IME_POLICY_FALLBACK_DISPLAY
    }.getOrDefault(-1)

    fun setDisplayImePolicy(displayId: Int, policy: Int): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            manager.javaClass.getMethod("setDisplayImePolicy", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(manager, displayId, policy)
        } else if (policy != DISPLAY_IME_POLICY_HIDE) {
            manager.javaClass.getMethod("setShouldShowIme", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
                .invoke(manager, displayId, policy == DISPLAY_IME_POLICY_LOCAL)
        }
        true
    }.getOrDefault(false)

    companion object {
        const val DISPLAY_IME_POLICY_LOCAL = 0
        const val DISPLAY_IME_POLICY_FALLBACK_DISPLAY = 1
        const val DISPLAY_IME_POLICY_HIDE = 2

        private val manager: IInterface by lazy {
            val binder = Class.forName("android.os.ServiceManager").getDeclaredMethod("getService", String::class.java)
                .apply { isAccessible = true }.invoke(null, "window") as? IBinder
                ?: throw IllegalStateException("window_service_unavailable")
            val stub = Class.forName("android.view.IWindowManager\$Stub")
            stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder) as IInterface
        }
    }
}
