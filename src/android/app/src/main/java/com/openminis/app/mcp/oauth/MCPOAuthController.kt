package com.openminis.app.mcp.oauth

import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.openminis.app.auth.OAuthCallbackServer
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * [T-android-mcp-oauth] Drives the MCP Static-OAuth PKCE Authorization Code
 * flow (RFC 7636 + RFC 8252 loopback redirect + RFC 8707 resource). Android
 * port of iOS `MCPOAuthController.authorize`, reusing the app's existing
 * [OAuthCallbackServer] + Chrome Custom Tab pattern (same as ClaudeOAuthManager).
 *
 * Phase 2 scope: authorize() runs the browser round-trip and token exchange and
 * stores the issued tokens in [MCPOAuthStore]. The in-guest transport bridge
 * (materializing refresh material for minis-mcp-cli) and autonomous refresh are
 * intentionally out of this phase.
 */
class MCPOAuthController(private val context: Context) {

    sealed class Result {
        object Success : Result()
        object Cancelled : Result()
        data class Failed(val message: String) : Result()
    }

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    @Volatile
    private var callbackServer: OAuthCallbackServer? = null

    /**
     * Run the full authorize flow for [server] against [oauth]. [serverUrl] is
     * the server's own URL, used verbatim as the RFC 8707 resource indicator
     * (canonicalized). Opens the authorization page in a Custom Tab, waits (up
     * to 5 min) for the loopback callback, verifies state, exchanges the code,
     * and persists tokens. Never throws — returns a [Result].
     */
    suspend fun authorize(server: String, serverUrl: String?, oauth: MCPOAuthConfig): Result {
        if (!oauth.isConfigured) {
            return Result.Failed("OAuth is not fully configured (client id + endpoints required).")
        }
        val redirect = redirectUri(oauth)
        redirectProblem(redirect)?.let { return Result.Failed(it) }
        val redirectUri = URI(redirect)
        val port = redirectUri.port
        val redirectPath = redirectUri.path
        val resource = McpPkce.canonicalResourceUri(serverUrl)
        val pkce = McpPkce.newPkce()
        val authUrl = McpPkce.buildAuthorizationUrl(
            authorizationEndpoint = oauth.authorizationEndpoint,
            clientId = oauth.clientId,
            redirectUri = redirect,
            pkce = pkce,
            resource = resource,
            scopes = oauth.scopes,
        )

        return withContext(Dispatchers.IO) {
            callbackServer?.stop()
            callbackServer = null

            val callback = try {
              // The wait is bounded: a denied page, a closed tab or a port that cannot be bound must
              // not leave the settings screen busy forever.
              kotlinx.coroutines.withTimeout(AUTHORIZE_TIMEOUT_MS) {
                suspendCancellableCoroutine<Pair<String, String?>?> { cont ->
                    // Only the exact path and the state we issued may complete the login; anything
                    // else is refused and the listener keeps waiting for the real callback.
                    val srv = OAuthCallbackServer(
                        port = port,
                        expectedPath = redirectPath,
                        expectedState = pkce.state,
                    ) { code, state ->
                        if (cont.isActive) cont.resume(code to state)
                    }
                    callbackServer = srv
                    // If the user dismisses the Custom Tab, stop() fires this so
                    // we don't hang until the OS eventually tears the socket down.
                    srv.onExternalCancel = { if (cont.isActive) cont.resume(null) }
                    srv.start()
                    AppLogger.info(TAG, "[Authorize] '$server' callback server on :$port")

                    cont.invokeOnCancellation {
                        srv.stop()
                        callbackServer = null
                    }

                    // Do NOT set FLAG_ACTIVITY_NEW_TASK — MainActivity is
                    // singleTask (matches ClaudeOAuthManager's note about IME).
                    CustomTabsIntent.Builder().setShowTitle(true).build()
                        .launchUrl(context, Uri.parse(authUrl))
                    AppLogger.info(TAG, "[Authorize] '$server' opened Custom Tab")
                }
              }
            } catch (timeout: kotlinx.coroutines.TimeoutCancellationException) {
                return@withContext Result.Failed("Timed out waiting for the authorization to finish.")
            } finally {
                callbackServer?.stop()
                callbackServer = null
            } ?: return@withContext Result.Cancelled

            val (code, state) = callback
            if (state != pkce.state) {
                AppLogger.warning(TAG, "[Authorize] '$server' state mismatch")
                return@withContext Result.Failed("State mismatch in the OAuth callback.")
            }

            exchangeCode(server, oauth, redirect, resource, code, pkce.verifier)
        }
    }

    private suspend fun exchangeCode(
        server: String,
        oauth: MCPOAuthConfig,
        redirect: String,
        resource: String?,
        code: String,
        verifier: String,
    ): Result {
        val body = buildTokenExchangeBody(
            code = code,
            redirect = redirect,
            clientId = oauth.clientId,
            verifier = verifier,
            clientSecret = MCPOAuthStore.clientSecret(context, server),
            resource = resource,
        )
        return try {
            // Built inside the try: a token endpoint that is not a valid URL must end the flow with a
            // failure, not throw out of authorize().
            val request = Request.Builder()
                .url(oauth.tokenEndpoint)
                .post(body.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
            executeCancellable(request).use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    AppLogger.error(TAG, "[Authorize] '$server' token exchange HTTP ${resp.code}")
                    return Result.Failed("Token exchange failed (${resp.code}): ${text.take(200)}")
                }
                val json = JSONObject(text)
                val access = json.optString("access_token", "")
                if (access.isEmpty()) {
                    return Result.Failed("Token endpoint returned no access_token.")
                }
                // The user may have closed the screen while the endpoint was answering: a cancelled
                // authorization must not still save its token.
                kotlin.coroutines.coroutineContext.ensureActive()
                val expiresIn = json.optLong("expires_in", 0L)
                val expiresAt = if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000 else 0L
                MCPOAuthStore.setTokens(
                    context, server,
                    MCPOAuthStore.StoredTokens(
                        accessToken = access,
                        refreshToken = json.optString("refresh_token", "").ifBlank { null },
                        expiresAtMs = expiresAt,
                    ),
                )
                AppLogger.info(
                    TAG,
                    "[Authorize] '$server' OK (hasRefresh=${json.has("refresh_token")}, expiresIn=${expiresIn}s)",
                )
                Result.Success
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            AppLogger.error(TAG, "[Authorize] '$server' token exchange failed: ${t.message}")
            Result.Failed("Token exchange failed: ${t.message}")
        }
    }

    /** Runs [request] so that cancelling the coroutine cancels the HTTP call. */
    private suspend fun executeCancellable(request: Request): okhttp3.Response =
        suspendCancellableCoroutine { cont ->
            val call = http.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                    if (cont.isActive) cont.resumeWith(kotlin.Result.failure(e))
                }

                override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                    if (cont.isActive) cont.resume(response) else response.close()
                }
            })
        }

    private fun redirectUri(oauth: MCPOAuthConfig): String =
        oauth.redirectUri?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_REDIRECT_URI

    companion object {
        private const val TAG = "MCPOAuthController"

        /**
         * Assemble the form-urlencoded token-exchange body for the
         * authorization_code grant (MCP auth spec / RFC 6749). client_secret is
         * included only when present; the RFC 8707 `resource` only when non-null.
         * Extracted for unit testing. Mirrors iOS authorize()'s token form.
         */
        internal fun buildTokenExchangeBody(
            code: String,
            redirect: String,
            clientId: String,
            verifier: String,
            clientSecret: String?,
            resource: String?,
        ): String {
            val form = linkedMapOf(
                "grant_type" to "authorization_code",
                "code" to code,
                "redirect_uri" to redirect,
                "client_id" to clientId,
                "code_verifier" to verifier,
            )
            if (!clientSecret.isNullOrEmpty()) form["client_secret"] = clientSecret
            if (!resource.isNullOrEmpty()) form["resource"] = resource
            return form.entries.joinToString("&") { (k, v) ->
                "$k=${URLEncoder.encode(v, "UTF-8")}"
            }
        }

        /** How long the browser round trip may take before the login is abandoned. */
        internal const val AUTHORIZE_TIMEOUT_MS = 5 * 60 * 1000L

        /**
         * Why [redirect] cannot work with the listener this controller starts (plain HTTP on
         * loopback at an explicit port), or null if it can. Anything else would be sent to the
         * authorization server and then never reach us.
         */
        internal fun redirectProblem(redirect: String): String? {
            val uri = runCatching { URI(redirect) }.getOrNull()
                ?: return "Redirect URI is not a valid URL."
            val host = uri.host?.lowercase()
            if (host != "localhost" && host != "127.0.0.1") {
                return "Redirect URI must be a loopback address, e.g. $DEFAULT_REDIRECT_URI."
            }
            if (uri.scheme != "http") {
                return "Redirect URI must use http (the local listener has no TLS), e.g. $DEFAULT_REDIRECT_URI."
            }
            if (uri.port <= 0) {
                return "Redirect URI must name a port, e.g. $DEFAULT_REDIRECT_URI."
            }
            if (uri.path.isNullOrEmpty()) {
                return "Redirect URI must have a path, e.g. $DEFAULT_REDIRECT_URI."
            }
            return null
        }

        /** Fixed loopback port for MCP OAuth — distinct from ClaudeOAuthManager's
         *  54545 so a concurrent login never collides. Mirrors iOS 54546. */
        const val LOOPBACK_PORT = 54546
        const val DEFAULT_REDIRECT_URI = "http://localhost:$LOOPBACK_PORT/callback"
    }
}
