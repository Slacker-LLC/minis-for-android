package com.openminis.app.ui.browser

/**
 * Pure URL classification shared by chat links and WebView navigation.
 *
 * Keep this free of Android framework types so the edge cases can be covered
 * by ordinary JVM unit tests. Unknown but syntactically valid schemes are
 * classified as [Kind.EXTERNAL] so [BrowserExternalSchemeHandler] can
 * intercept them and apply its allow/block policy instead of letting WebView
 * render ERR_UNKNOWN_URL_SCHEME.
 */
internal object BrowserUrlPolicy {
    enum class Kind {
        INTERNAL,
        EXTERNAL,
        INVALID,
    }

    private val schemePrefix = Regex("^([A-Za-z][A-Za-z0-9+.-]*):")
    private val internalSchemes = setOf("http", "https", "about", "file")

    fun classify(rawUrl: String?): Kind {
        val value = rawUrl?.trim().orEmpty()
        if (value.isEmpty() || value.any { it.isISOControl() || it.isWhitespace() }) {
            return Kind.INVALID
        }

        val scheme = schemeOf(value) ?: return Kind.INVALID
        if (scheme !in internalSchemes) return Kind.EXTERNAL

        return when (scheme) {
            "http", "https" -> if (hasHttpAuthority(value)) Kind.INTERNAL else Kind.INVALID
            else -> Kind.INTERNAL
        }
    }

    fun schemeOf(rawUrl: String?): String? {
        val value = rawUrl?.trim().orEmpty()
        return schemePrefix.find(value)?.groupValues?.getOrNull(1)?.lowercase()
    }

    private fun hasHttpAuthority(value: String): Boolean {
        val separator = value.indexOf("://")
        if (separator < 0) return false
        val authorityStart = separator + 3
        if (authorityStart >= value.length) return false
        val authorityEnd = value.indexOfAny(charArrayOf('/', '?', '#'), authorityStart)
            .let { if (it < 0) value.length else it }
        return authorityEnd > authorityStart
    }
}
