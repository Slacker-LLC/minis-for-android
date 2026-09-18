package com.openminis.app.tools.android

/** Only the `wm size` override is the logical coordinate space used by input and screencap. */
object AndroidDisplaySizeParser {
    fun parse(output: String): Pair<Int, Int>? =
        parseLabel(output, "Override size") ?: parseLabel(output, "Physical size")

    /** The `wm size` override only; null when the device runs at its physical size. */
    fun parseOverride(output: String): Pair<Int, Int>? = parseLabel(output, "Override size")

    private fun parseLabel(output: String, label: String): Pair<Int, Int>? {
        val match = Regex("""(?m)^\s*${Regex.escape(label)}:\s*(\d+)x(\d+)\s*$""")
            .find(output) ?: return null
        val width = match.groupValues[1].toIntOrNull() ?: return null
        val height = match.groupValues[2].toIntOrNull() ?: return null
        return (width to height).takeIf { width > 0 && height > 0 }
    }
}
