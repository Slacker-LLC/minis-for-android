/*
 * Adapted for Minis from ShadowAuto (`android-notes/ShadowAuto`, commit `611e5eb0e1c94befda3c17c97438912dfd30e8a8`); modified.
 * Original source: android-shell/src/main/java/com/silentauto/shell/ClipboardBridge.java.
 * Licensed under Apache-2.0; see third_party/shadowauto/LICENSE.
 */
package com.openminis.app.tools.android.vscreen.service.internal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/** Clipboard access is process-local and is deliberately not exposed as a VScreen AIDL operation. */
internal class ClipboardBridge {
    private val manager = ShellContext.get().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        ?: throw IllegalStateException("clipboard_service_unavailable")

    fun getText(): String {
        val clip = manager.primaryClip ?: return ""
        if (clip.itemCount == 0) return ""
        return clip.getItemAt(0).coerceToText(ShellContext.get())?.toString().orEmpty()
    }

    fun setText(value: String) {
        manager.setPrimaryClip(ClipData.newPlainText("Minis VScreen", value))
    }
}
