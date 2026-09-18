package com.openminis.app.provider

/**
 * [T-eta-provider-passthrough] Single policy for caller-supplied HTTP headers on
 * every passthrough surface (`extra_headers`, image passthrough,
 * `passthrough.headers`).
 *
 * Ported from Eta `agent/model/CustomHeaderFilter.kt` (Mangi-11/Eta @ c15de97);
 * attribution in THIRD_PARTY_LICENSES.md.
 *
 * Why the filter exists: on these paths the caller adds headers to a request the
 * APP already authenticates. A caller-set `Host`/`Content-Length`/`Connection`
 * corrupts HTTP framing, and a caller-set `Authorization`/`x-api-key`/
 * `anthropic-version` silently replaces the credential the app supplies — so
 * both families are dropped here instead of reaching the wire.
 *
 * Names are compared case-insensitively (HTTP header names are), so `X-Foo` and
 * `x-foo` collapse to one header instead of both being sent.
 *
 * Dropping is fail-closed: an invalid header is removed and reported through
 * [sanitizeWithWarnings]; nothing is rewritten into a "best effort" variant.
 */
object CustomHeaderPolicy {

    /** Header names callers may never set (case-insensitive). */
    val FORBIDDEN_NAMES: Set<String> = setOf(
        // Protocol/framing headers owned by the HTTP stack.
        "host",
        "content-length",
        "connection",
        "transfer-encoding",
        "content-encoding",
        "accept-encoding",
        "expect",
        "keep-alive",
        "proxy-connection",
        "upgrade",
        // Credential headers owned by the provider instance.
        "authorization",
        "x-api-key",
        "anthropic-version",
    )

    /** Header names whose values must never reach a log line (case-insensitive). */
    val SENSITIVE_NAMES: Set<String> = setOf(
        "authorization",
        "x-api-key",
        "api-key",
    )

    /** RFC 7230 token charset — the only characters a header name may contain. */
    private val NAME_PATTERN = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+")

    data class Result(
        val headers: Map<String, String>,
        val warnings: List<String>,
    )

    fun isForbidden(name: String): Boolean =
        name.isBlank() || name.trim().lowercase() in FORBIDDEN_NAMES

    fun sanitize(headers: Map<String, String>): Map<String, String> =
        sanitizeWithWarnings(headers).headers

    /**
     * Keeps every usable header and reports the rest. One warning per removed
     * header, plus one when a case-insensitive duplicate replaced an earlier one.
     */
    fun sanitizeWithWarnings(headers: Map<String, String>): Result {
        val kept = LinkedHashMap<String, String>()
        val keptByLowercase = HashMap<String, String>()
        val warnings = mutableListOf<String>()
        for ((rawName, value) in headers) {
            val name = rawName.trim()
            when {
                isForbidden(name) -> warnings.add(
                    "header '$rawName' was ignored: the name is empty or managed by the system " +
                        "(protocol or credential header).",
                )
                !NAME_PATTERN.matches(name) -> warnings.add(
                    "header '$rawName' was ignored: the name contains characters that are not " +
                        "valid in an HTTP header name.",
                )
                value.any { it != '\t' && it !in ' '..'~' } -> warnings.add(
                    "header '$rawName' was ignored: the value may only contain printable ASCII " +
                        "characters or tabs.",
                )
                else -> {
                    val lowercase = name.lowercase()
                    val replaced = keptByLowercase[lowercase]
                    if (replaced != null) {
                        kept.remove(replaced)
                        warnings.add(
                            "header '$name' replaced the earlier '$replaced': HTTP header names " +
                                "are case-insensitive.",
                        )
                    }
                    kept[name] = value
                    keptByLowercase[lowercase] = name
                }
            }
        }
        return Result(kept, warnings)
    }

    /** Copy of [headers] with credential values replaced by `***`. */
    fun redactForLog(headers: Map<String, String>): Map<String, String> =
        headers.mapValues { (name, value) ->
            if (name.trim().lowercase() in SENSITIVE_NAMES) "***" else value
        }
}
