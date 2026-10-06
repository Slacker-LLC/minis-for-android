package com.openminis.app.auth

import org.json.JSONObject

/**
 * When a failed token refresh means the grant is gone, and how early to refresh.
 *
 * A credential is deleted only for an explicit revocation error from the token endpoint. A timeout,
 * a 5xx, a bare 403 (a subscription problem, not a revoked token) or an error whose text merely
 * mentions "refresh_token" are not revocations: deleting the long-lived credential then forces a
 * new sign-in for what may be a few seconds of bad network.
 */
internal object OAuthRefreshPolicy {
    private val REVOKED_ERRORS = setOf("invalid_grant", "invalid_token", "refresh_token_reused", "token_revoked")

    private const val MAX_LEAD_MS = 4 * 3600 * 1000L
    private const val MIN_LEAD_MS = 5 * 60 * 1000L

    /** The OAuth `error` code in [body], from `{"error":"x"}` or `{"error":{"type"|"code":"x"}}`; null if none. */
    fun errorCode(body: String): String? {
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return null
        return when (val error = json.opt("error")) {
            is String -> error
            is JSONObject -> error.optString("type").ifEmpty { error.optString("code") }.ifEmpty { null }
            else -> null
        }?.lowercase()
    }

    /** True only for a 4xx whose error code says the refresh token is invalid, revoked or reused. */
    fun isRevoked(status: Int, body: String): Boolean =
        status in 400..403 && errorCode(body) in REVOKED_ERRORS

    /**
     * How long before expiry to refresh a token that lives [expiresInSeconds]: a quarter of its
     * life, between 5 minutes and 4 hours. A flat 4 hours made a one-hour token refresh on every use.
     */
    fun refreshLeadMs(expiresInSeconds: Long): Long {
        val lifetimeMs = expiresInSeconds.coerceAtLeast(0) * 1000
        if (lifetimeMs == 0L) return MIN_LEAD_MS
        return (lifetimeMs / 4).coerceIn(MIN_LEAD_MS, MAX_LEAD_MS)
    }
}
