package com.openminis.app.accessibility

/**
 * [T-eta-text-insert] How much text one write may carry, and what an insertion may not be.
 *
 * Ported from Eta `RootShellDeviceController` (agent/device/RootShellDeviceController.kt @
 * c15de97); attribution in THIRD_PARTY_LICENSES.md. Upstream refuses an oversized write before it
 * touches the device - an insertion carries at most 1000 characters and a whole-value write at most
 * 4000 - and an insertion may not be empty, while an empty whole-value write is exactly how a field
 * is cleared. Keeping this as values means the tool layer refuses the same way without a device.
 */
object TextInputBoundsPolicy {

    /** The bound on an insertion (the input_text and paste_text actions). */
    const val MAX_INSERT_CHARS = 1_000

    /** The bound on a whole-value write (the set_text action). */
    const val MAX_REPLACE_CHARS = 4_000

    data class Refusal(val code: String, val message: String)

    /** Null when the insertion may proceed: empty insertions and oversized ones are refused. */
    fun insertRefusal(text: String): Refusal? = when {
        text.isEmpty() -> Refusal(
            "INVALID_ARGUMENT",
            "text must not be empty; send the full value with set_text",
        )
        text.length > MAX_INSERT_CHARS -> Refusal(
            "TEXT_TOO_LONG",
            "an insertion carries at most " + MAX_INSERT_CHARS +
                " characters; send the full value with set_text",
        )
        else -> null
    }

    /** Null when the write may proceed. An empty value is allowed: that is how a field is cleared. */
    fun replaceRefusal(text: String): Refusal? =
        if (text.length > MAX_REPLACE_CHARS) {
            Refusal(
                "TEXT_TOO_LONG",
                "a text write carries at most " + MAX_REPLACE_CHARS + " characters",
            )
        } else {
            null
        }
}
