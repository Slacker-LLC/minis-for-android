package com.openminis.app.tools.android

/**
 * [T-eta-wait-match] How a waited-for text is matched against a node.
 *
 * Ported from Eta `RootShellDeviceController.matches` (agent/device/RootShellDeviceController.kt @
 * c15de97); attribution in THIRD_PARTY_LICENSES.md. Upstream's own rules are kept exactly, including
 * the case behaviour that is easy to "improve" by accident: the default is a case-insensitive
 * contains, while exact and prefix compare the value as it stands, and a regular expression that
 * does not compile simply does not match instead of failing the wait.
 *
 * One deliberate difference: upstream treats any unrecognised mode as the default, which turns a
 * typo into a silently different search. Here an unknown mode is refused so the caller learns the
 * vocabulary.
 */
object UiWaitPolicy {

    const val MODE_CONTAINS = "contains"
    const val MODE_EXACT = "exact"
    const val MODE_PREFIX = "prefix"
    const val MODE_REGEX = "regex"

    val MODES: List<String> = listOf(MODE_CONTAINS, MODE_EXACT, MODE_PREFIX, MODE_REGEX)

    /** The normalised mode, or null when it is not one this policy knows. */
    fun parse(raw: String?): String? {
        val mode = raw?.trim()?.lowercase().orEmpty().ifEmpty { MODE_CONTAINS }
        return mode.takeIf { it in MODES }
    }

    fun matches(value: String?, needle: String, mode: String): Boolean = when (mode) {
        MODE_EXACT -> value == needle
        MODE_PREFIX -> value?.startsWith(needle) == true
        MODE_REGEX -> runCatching { Regex(needle).containsMatchIn(value.orEmpty()) }
            .getOrDefault(false)
        else -> value?.contains(needle, ignoreCase = true) == true
    }
}
