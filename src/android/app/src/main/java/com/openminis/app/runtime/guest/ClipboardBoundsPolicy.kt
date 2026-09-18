package com.openminis.app.runtime.guest

/**
 * [T-eta-clipboard-bounds] How much of the clipboard is handed on, and how much may be written.
 *
 * Ported from Eta `RootShellDeviceController` (agent/device/RootShellDeviceController.kt @
 * c15de97); attribution in THIRD_PARTY_LICENSES.md. A clipboard holds whatever the user last copied,
 * which can be a whole document: a read that hands all of it to the model is a context budget spent
 * by accident, and a write that accepts anything is a paste waiting to happen in somebody's field.
 * Upstream bounds both - 8,000 characters on a read with the truncation reported, 20,000 on a write
 * refused before the clipboard is touched - and both rules live here so they can be tested without
 * a device or a clipboard.
 */
object ClipboardBoundsPolicy {

    /** What a read may hand on; anything longer is truncated and reported as such. */
    const val MAX_READ_CHARS = 8_000

    /** What a write may carry; anything longer is refused before the clipboard changes. */
    const val MAX_WRITE_CHARS = 20_000

    data class Read(val text: String, val truncated: Boolean)

    fun read(text: String): Read = if (text.length > MAX_READ_CHARS) {
        Read(text.take(MAX_READ_CHARS), truncated = true)
    } else {
        Read(text, truncated = false)
    }

    /** Null when the write may proceed; otherwise the reason to refuse it. */
    fun writeRefusal(text: String): String? =
        if (text.length > MAX_WRITE_CHARS) {
            "clipboard text carries at most " + MAX_WRITE_CHARS + " characters (got " +
                text.length + ")"
        } else {
            null
        }
}
