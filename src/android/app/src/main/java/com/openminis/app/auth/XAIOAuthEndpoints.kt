package com.openminis.app.auth

import org.json.JSONObject
import java.net.URI

/**
 * xAI OAuth 的纯逻辑：端点信任校验、discovery 解析、令牌包整理。不碰网络、不依赖 Android，
 * 由 XAIOAuthEndpointsTest 覆盖（含拒绝路径）。
 *
 * 流程事实（2026-10-17 对 auth.x.ai 的真实探测 + 上游 openclaw `extensions/xai/xai-oauth.ts`）：
 * - discovery 文档的 `device_authorization_endpoint` / `token_endpoint` 才是当前端点
 *   （`/oauth2/device/code`、`/oauth2/token`）。
 * - 旧的 `/oauth/authorize`、`/oauth/token` 已下线，会返回 403；老版本缓存下来的 token_endpoint
 *   可能还指向它，所以刷新前必须识别并重新发现。
 * - 所有端点与验证页必须是 https 且主机为 `x.ai` 或 `*.x.ai`。
 */
internal object XAIOAuthEndpoints {

    const val ISSUER = "https://auth.x.ai"
    const val DISCOVERY_URL = "$ISSUER/.well-known/openid-configuration"

    /** 已下线的旧令牌端点（上游 `XAI_LEGACY_OAUTH_TOKEN_ENDPOINT`）。 */
    const val LEGACY_TOKEN_ENDPOINT = "$ISSUER/oauth/token"

    data class Discovery(val deviceAuthorizationEndpoint: String, val tokenEndpoint: String)

    /** https 且主机恰为 `x.ai` 或其子域；userinfo 欺骗（`https://x.ai@evil.com`）按真实主机判断。 */
    fun isTrusted(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        if (!"https".equals(uri.scheme, ignoreCase = true)) return false
        val host = uri.host?.lowercase() ?: return false
        return host == "x.ai" || host.endsWith(".x.ai")
    }

    /** 是否就是已下线的 `https://auth.x.ai/oauth/token`（忽略 query / fragment）。 */
    fun isLegacyTokenEndpoint(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return "$scheme://$host${uri.rawPath.orEmpty()}" == LEGACY_TOKEN_ENDPOINT
    }

    /** 缓存的令牌端点可否直接用于刷新：可信且不是旧端点。 */
    fun isUsableCachedTokenEndpoint(url: String?): Boolean =
        !url.isNullOrEmpty() && isTrusted(url) && !isLegacyTokenEndpoint(url)

    /** 解析 discovery 文档。缺字段、不可信、或仍指向旧端点都抛 [IllegalStateException]。 */
    fun parseDiscovery(json: JSONObject): Discovery {
        val device = json.optString("device_authorization_endpoint", "")
        val token = json.optString("token_endpoint", "")
        check(device.isNotEmpty() && token.isNotEmpty()) {
            "xAI OIDC discovery is missing the device authorization or token endpoint"
        }
        check(isTrusted(device)) { "xAI OIDC discovery returned an untrusted device authorization endpoint" }
        check(isTrusted(token)) { "xAI OIDC discovery returned an untrusted token endpoint" }
        check(!isLegacyTokenEndpoint(token)) { "xAI OIDC discovery returned the retired token endpoint" }
        return Discovery(device, token)
    }

    /** 验证页（含预填的 complete 版本）都可信才放行，否则 null，调用方当作失败处理。 */
    fun trustedAuthorization(auth: KimiDeviceFlow.DeviceAuthorization): KimiDeviceFlow.DeviceAuthorization? {
        if (!isTrusted(auth.verificationUri)) return null
        if (auth.verificationUriComplete?.let { isTrusted(it) } == false) return null
        return auth
    }

    /**
     * 把令牌端点响应整理成要存的令牌包：保留 `id_token` 等原字段，加 `expire_at`。
     * 登录时 [requireRefreshToken] 为 true——没有 refresh_token 的登录过期后无法续期，宁可当场报错。
     * 刷新时响应可省略 refresh_token，沿用 [previousRefreshToken]。
     */
    fun buildTokenBundle(
        response: JSONObject,
        nowMs: Long,
        requireRefreshToken: Boolean,
        previousRefreshToken: String? = null,
    ): JSONObject {
        val bundle = JSONObject(response.toString())
        check(bundle.optString("access_token", "").isNotEmpty()) {
            "xAI OAuth token response is missing access_token"
        }
        if (bundle.optString("refresh_token", "").isEmpty()) {
            bundle.remove("refresh_token")
            if (!previousRefreshToken.isNullOrEmpty()) {
                bundle.put("refresh_token", previousRefreshToken)
            } else if (requireRefreshToken) {
                error(
                    "xAI OAuth token response is missing refresh_token; " +
                        "the OAuth client did not issue offline access",
                )
            }
        }
        val expiresIn = bundle.optLong("expires_in", 0)
        if (expiresIn > 0) bundle.put("expire_at", nowMs + expiresIn * 1000)
        return bundle
    }
}
