package com.openminis.app.tools.android

/** Prevents accessibility observations from serializing values in password nodes. */
internal object UiSensitiveValuePolicy {
    fun redactAccessibilityValue(isPassword: Boolean, value: String): String =
        if (isPassword) "" else value
}
