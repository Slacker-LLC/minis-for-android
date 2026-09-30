package com.openminis.app.tools.android.vscreen

import java.nio.charset.StandardCharsets

internal object VirtualScreenPolicy {
    const val PHYSICAL_DISPLAY_REFUSED = "physical_display_refused"
    const val DISPLAY_GONE = "vscreen_display_gone"
    const val UNKNOWN_DISPLAY = "unknown_display"
    const val UNAVAILABLE = "vscreen_unavailable"
    const val APP_LEFT_DISPLAY = "app_left_virtual_display"
    const val SCREENSHOT_BLOCKED_SECURE = "screenshot_blocked_secure"
    const val MAX_DUMP_BYTES = 64 * 1024
    const val MAX_SCREENSHOT_BYTES = 1_572_864

    fun disabledToolError(enabled: Boolean): String? = if (enabled) null else UNAVAILABLE

    data class FlagCandidate(val flags: Int, val label: String)

    /** IDs from callers are never allowed to address Android's physical display. */
    fun displayError(displayId: Int, activeDisplayId: Int?): String? = when {
        displayId == 0 -> PHYSICAL_DISPLAY_REFUSED
        activeDisplayId == null -> DISPLAY_GONE
        displayId != activeDisplayId -> UNKNOWN_DISPLAY
        else -> null
    }

    /** Match ShadowAuto's ordered hidden-flag fallback; duplicate masks are removed. */
    fun flagCandidates(base: Int, supportsTouch: Int, ownFocus: Int, trusted: Int): List<FlagCandidate> {
        val masks = listOf(
            base or supportsTouch or ownFocus or trusted,
            base or supportsTouch or ownFocus,
            base or supportsTouch,
            base,
        )
        val seen = HashSet<Int>()
        return masks.filter { seen.add(it) }.map { mask ->
            val active = listOf("TRUSTED" to trusted, "OWN_FOCUS" to ownFocus, "SUPPORTS_TOUCH" to supportsTouch)
                .filter { (name, value) -> value != 0 && name.isNotEmpty() && mask and value == value }
                .map { it.first }
            FlagCandidate(mask, active.takeIf { it.isNotEmpty() }?.joinToString("|") ?: "BASE")
        }
    }

    /** Keep an oversized diagnostic valid JSON and mark that its tree was truncated. */
    fun fitDumpJson(json: String, maxBytes: Int = MAX_DUMP_BYTES): String {
        require(maxBytes >= 32)
        if (json.toByteArray(StandardCharsets.UTF_8).size <= maxBytes) return json
        val prefix = "{\"truncated\":true,\"partial\":\""
        val suffix = "\"}"
        val budget = maxBytes - prefix.length - suffix.length
        val escaped = StringBuilder()
        var used = 0
        var index = 0
        while (index < json.length) {
            val cp = json.codePointAt(index)
            val raw = String(Character.toChars(cp))
            val encoded = when (cp) {
                '\\'.code -> "\\\\"
                '"'.code -> "\\\""
                '\n'.code -> "\\n"
                '\r'.code -> "\\r"
                '\t'.code -> "\\t"
                else -> if (cp < 0x20) "\\u%04x".format(cp) else raw
            }
            val size = encoded.toByteArray(StandardCharsets.UTF_8).size
            if (used + size > budget) break
            escaped.append(encoded)
            used += size
            index += Character.charCount(cp)
        }
        return prefix + escaped + suffix
    }
}
