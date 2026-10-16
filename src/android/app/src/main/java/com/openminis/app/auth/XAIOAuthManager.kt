package com.openminis.app.auth

import android.content.Context
import android.net.Uri
import android.util.Log
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.saveApiKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.Base64

/**
 * xAI (Grok) OAuth manager —— RFC 8628 设备码授权（Device Authorization Grant）。
 *
 * 为什么不再用回环回调（127.0.0.1:56121 + Custom Tab + PKCE）：
 * - 旧 `/oauth/authorize`、`/oauth/token` 已下线（403）；当前端点来自 OIDC discovery。
 * - 回环流程靠 Custom Tab 关闭检测、前台门、固定端口，脆弱；上游 openclaw 因大量用户反馈已整体移除，
 *   只保留设备码（`extensions/xai/xai-oauth.ts` 的 `requestXaiDeviceCode` / `pollXaiDeviceCodeToken`）。
 *
 * 流程：discovery → `POST device_authorization_endpoint`（client_id + scope）→ 界面展示用户码与验证页
 * → 轮询 `token_endpoint`（device_code grant）。app 退到浏览器时网络可能被系统拒绝，轮询对
 * IOException 容忍到截止时间，回到前台自然恢复。
 *
 * 客户端沿用上游同一个 Grok CLI 公共 client id（同意页会显示 Grok Build）；这是共享客户端，xAI 可随时收紧。
 *
 * 端点解析、信任校验、令牌包整理在 [XAIOAuthEndpoints]（纯函数，有单测）。
 */
class XAIOAuthManager(context: Context, instanceId: String) : OAuthManager(context, instanceId) {

    companion object {
        private const val TAG = "XAIOAuth"

        const val OAUTH_CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828"
        const val DISCOVERY_URL = XAIOAuthEndpoints.DISCOVERY_URL
        const val OAUTH_SCOPES = "openid profile email offline_access grok-cli:access api:access"

        private const val DEVICE_CODE_GRANT = "urn:ietf:params:oauth:grant-type:device_code"
        private const val KEY_TOKEN_ENDPOINT = "token_endpoint"

        /** 每实例刷新互斥，单飞（同 KimiOAuthManager）：并发请求不会各拿一个旧 refresh_token 去换。 */
        private val refreshMutexes = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
        private fun mutexFor(instanceId: String): Mutex = refreshMutexes.getOrPut(instanceId) { Mutex() }

        /**
         * 完整设备码登录。[onDeviceCode] 在设备码签发后立刻回调（界面展示用户码与验证页）；
         * 此时登录尚未完成，随后挂起轮询直到用户授权或终态失败。令牌经
         * [ProviderRepository.saveApiKey] 写入，沿用现有 `Authorization: Bearer …` 请求路径。
         */
        suspend fun login(
            context: Context,
            instanceId: String,
            providerRepository: ProviderRepository,
            onDeviceCode: (KimiDeviceFlow.DeviceAuthorization) -> Unit,
        ): String {
            val manager = XAIOAuthManager(context, instanceId)
            val token = manager.performDeviceLogin(onDeviceCode)
            providerRepository.saveApiKey(instanceId, token)
            return token
        }
    }

    // 基类抽象面：设备码流程不用重定向 / PKCE，只放不会被读到的占位。
    override val authURL = XAIOAuthEndpoints.ISSUER
    override val tokenURL = "${XAIOAuthEndpoints.ISSUER}/oauth2/token"
    override val clientId = OAUTH_CLIENT_ID
    override val clientSecret: String? = null
    override val callbackPort = 0
    override val redirectPath = ""
    override val scopes = OAUTH_SCOPES

    // id_token 解出的账号信息，与令牌包一并保存。
    var accountId: String?
        get() = loadOAuthString("account_id")
        private set(value) { value?.let { saveOAuthString("account_id", it) } }

    var email: String?
        get() = loadOAuthString("email")
        private set(value) { value?.let { saveOAuthString("email", it) } }

    var displayName: String?
        get() = loadOAuthString("display_name")
        private set(value) { value?.let { saveOAuthString("display_name", it) } }

    // ── HTTP ──────────────────────────────────────────────────────────

    /** 表单 POST，返回 (HTTP 状态, JSON)；响应不是 JSON（例如 Cloudflare 挑战页）时 JSON 为空对象。 */
    private fun postForm(url: String, params: Map<String, String>): Pair<Int, JSONObject> {
        val formBody = params.entries.joinToString("&") { "${it.key}=${Uri.encode(it.value)}" }
        val request = Request.Builder()
            .url(url)
            .post(formBody.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: ""
            val json = try { JSONObject(body) } catch (_: Exception) { JSONObject() }
            return response.code to json
        }
    }

    // ── Discovery ─────────────────────────────────────────────────────

    /** 拉取 discovery，校验后缓存令牌端点（刷新用）。 */
    private suspend fun fetchDiscovery(): XAIOAuthEndpoints.Discovery = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(XAIOAuthEndpoints.DISCOVERY_URL).get().build()
        val (code, body) = httpClient.newCall(request).execute().use { it.code to (it.body?.string() ?: "") }
        if (code !in 200..299 || body.isEmpty()) {
            throw IllegalStateException("xAI OIDC discovery failed: HTTP $code")
        }
        val discovery = XAIOAuthEndpoints.parseDiscovery(JSONObject(body))
        saveOAuthString(KEY_TOKEN_ENDPOINT, discovery.tokenEndpoint)
        discovery
    }

    /**
     * 刷新用的令牌端点。缓存缺失、不可信、或仍是已下线的 `/oauth/token`（老版本登录留下的）
     * 都重新发现，并把当前端点写回缓存——否则老登录的刷新会一直撞 403。
     */
    private suspend fun resolveTokenEndpoint(): String {
        val cached = loadOAuthString(KEY_TOKEN_ENDPOINT)
        if (XAIOAuthEndpoints.isUsableCachedTokenEndpoint(cached)) return cached!!
        return fetchDiscovery().tokenEndpoint
    }

    // ── 设备码登录 ────────────────────────────────────────────────────

    /** 第 1 步：discovery 之后申请设备码；验证页不在 x.ai 下则拒绝。 */
    suspend fun requestDeviceAuthorization(): Pair<KimiDeviceFlow.DeviceAuthorization, String> =
        withContext(Dispatchers.IO) {
            val discovery = fetchDiscovery()
            val (status, json) = postForm(
                discovery.deviceAuthorizationEndpoint,
                mapOf("client_id" to clientId, "scope" to scopes),
            )
            if (status !in 200..299) {
                val desc = json.optString("error_description", "")
                    .ifEmpty { json.optString("error", "device authorization failed") }
                Log.e(TAG, "device authorization failed: $status ${sanitizeBody(json.toString())}")
                throw IllegalStateException("xAI device authorization failed ($status): $desc")
            }
            val parsed = KimiDeviceFlow.parseDeviceAuthorization(json)
                ?: throw IllegalStateException("xAI device authorization response is missing required fields")
            val auth = XAIOAuthEndpoints.trustedAuthorization(parsed)
                ?: throw IllegalStateException("xAI device authorization returned an untrusted verification URL")
            auth to discovery.tokenEndpoint
        }

    /**
     * 第 2 步：轮询令牌端点直到授权 / 终态失败。网络错误（app 在浏览器时被系统限网）容忍到截止时间，
     * 但连续失败超过 [OAuthForegroundGate.MAX_WAIT_MS] 说明确实断网，按网络不可达报错。
     */
    private suspend fun pollForToken(auth: KimiDeviceFlow.DeviceAuthorization, tokenEndpoint: String): String =
        withContext(Dispatchers.IO) {
            val deadline = System.currentTimeMillis() + auth.expiresInSeconds * 1000
            var interval = auth.intervalSeconds.coerceAtLeast(1)
            var firstFailureAt = 0L
            while (System.currentTimeMillis() < deadline) {
                delay(interval * 1000)
                val (status, json) = try {
                    postForm(
                        tokenEndpoint,
                        mapOf(
                            "grant_type" to DEVICE_CODE_GRANT,
                            "client_id" to clientId,
                            "device_code" to auth.deviceCode,
                        ),
                    ).also { firstFailureAt = 0L }
                } catch (e: IOException) {
                    val now = System.currentTimeMillis()
                    if (firstFailureAt == 0L) firstFailureAt = now
                    if (now - firstFailureAt > OAuthForegroundGate.MAX_WAIT_MS) {
                        throw OAuthNetworkUnreachableException(e)
                    }
                    Log.w(TAG, "device poll failed, retrying: ${e.message}")
                    continue
                }
                when (val result = KimiDeviceFlow.classifyPoll(json, status in 200..299)) {
                    is KimiDeviceFlow.PollResult.Success -> {
                        val bundle = XAIOAuthEndpoints.buildTokenBundle(
                            json,
                            nowMs = System.currentTimeMillis(),
                            requireRefreshToken = true,
                        )
                        saveOAuthString("tokens", bundle.toString())
                        onTokensReceived(bundle)
                        Log.i(TAG, "xAI device login OK, expires in ${result.expiresInSeconds}s (instance: $instanceId)")
                        return@withContext result.accessToken
                    }
                    KimiDeviceFlow.PollResult.Pending -> Unit
                    KimiDeviceFlow.PollResult.SlowDown -> {
                        interval = KimiDeviceFlow.bumpedInterval(interval)
                        Log.d(TAG, "slow_down — interval now ${interval}s")
                    }
                    is KimiDeviceFlow.PollResult.Denied ->
                        throw IllegalStateException("xAI login denied: ${result.description}")
                    is KimiDeviceFlow.PollResult.Expired ->
                        throw IllegalStateException("xAI login code expired: ${result.description}")
                    is KimiDeviceFlow.PollResult.Fatal ->
                        throw IllegalStateException("xAI login failed (HTTP $status): ${result.description}")
                }
            }
            throw IllegalStateException("xAI login timed out")
        }

    /** 两步串联；[onDeviceCode] 把用户码 / 验证页交给界面（主线程）。 */
    suspend fun performDeviceLogin(onDeviceCode: (KimiDeviceFlow.DeviceAuthorization) -> Unit): String {
        val (auth, tokenEndpoint) = requestDeviceAuthorization()
        Log.i(TAG, "device code issued — interval=${auth.intervalSeconds}s expires=${auth.expiresInSeconds}s")
        withContext(Dispatchers.Main) { onDeviceCode(auth) }
        return pollForToken(auth, tokenEndpoint)
    }

    // ── id_token ──────────────────────────────────────────────────────

    override suspend fun onTokensReceived(json: JSONObject) {
        // email / 显示名 / 账号 id 都是可选信息，解析失败只影响设置页的展示。
        val idToken = json.optString("id_token", "").ifEmpty { json.optString("access_token", "") }
        if (idToken.isNotEmpty()) parseIdToken(idToken)
    }

    private fun parseIdToken(idToken: String) {
        try {
            val parts = idToken.split(".")
            if (parts.size < 2) return
            val payload = String(Base64.getUrlDecoder().decode(parts[1]))
            val json = JSONObject(payload)
            json.optString("email").takeIf { it.isNotEmpty() }?.let { email = it }
            json.optString("name").takeIf { it.isNotEmpty() }?.let { displayName = it }
            // `account_id` 是工作区 / 组织，`sub` 是用户 id；有哪个存哪个。
            (json.optString("account_id").takeIf { it.isNotEmpty() }
                ?: json.optString("sub").takeIf { it.isNotEmpty() })?.let { accountId = it }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse xAI id_token: ${e.message}")
        }
    }

    // ── 刷新（单飞） ──────────────────────────────────────────────────

    /**
     * 表单 POST 到（必要时重新发现的）令牌端点，公共客户端不带 client_secret。
     * 响应省略 `refresh_token` 时沿用旧的。裸 403（订阅层级无 API 权限，响应没有 OAuth 错误码）
     * 不算撤销，保留令牌包；只有明确的 invalid_grant 之类才会被基类判为撤销并清除。
     */
    override suspend fun refreshToken(): Boolean = withContext(Dispatchers.IO) {
        lastRefreshRevoked = false
        val seenExpireAt = loadStoredTokens()?.optLong("expire_at", 0) ?: 0
        mutexFor(instanceId).withLock {
            val stored = loadStoredTokens() ?: return@withLock false
            val refresh = stored.optString("refresh_token", "").ifEmpty { return@withLock false }
            // 等锁期间别的刷新者已换到更新的令牌：不再重复请求。
            val currentExpireAt = stored.optLong("expire_at", 0)
            if (currentExpireAt > seenExpireAt && currentExpireAt > System.currentTimeMillis()) {
                Log.d(TAG, "refresh skipped — fresher token already present (coalesced)")
                return@withLock true
            }
            try {
                val (status, json) = postForm(
                    resolveTokenEndpoint(),
                    mapOf("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to clientId),
                )
                if (status !in 200..299) {
                    Log.e(TAG, "xAI refresh failed: $status ${sanitizeBody(json.toString())}")
                    lastRefreshRevoked = OAuthRefreshPolicy.isRevoked(status, json.toString())
                    return@withLock false
                }
                val bundle = XAIOAuthEndpoints.buildTokenBundle(
                    json,
                    nowMs = System.currentTimeMillis(),
                    requireRefreshToken = false,
                    previousRefreshToken = refresh,
                )
                if (!storedRefreshTokenIs(refresh)) {
                    Log.w(TAG, "xAI refresh result discarded: the stored credential changed meanwhile")
                    return@withLock loadStoredTokens() != null
                }
                saveOAuthString("tokens", bundle.toString())
                true
            } catch (e: Exception) {
                Log.e(TAG, "xAI refresh error", e)
                false
            }
        }
    }
}
