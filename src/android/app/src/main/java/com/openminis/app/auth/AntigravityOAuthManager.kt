package com.openminis.app.auth

import android.content.Context
import android.util.Log
import com.openminis.app.BuildConfig
import com.openminis.app.data.repository.saveApiKey
import com.openminis.app.provider.antigravity.AntigravityWire
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Google sign-in for Antigravity: Google's Cloud Code Assist endpoint serving
 * Gemini, Claude and GPT models through one Google account.
 *
 * It uses the Antigravity IDE's own installed-app OAuth client, so the consent
 * screen and the free tier are the IDE's. Installed-app client secrets are not
 * confidential (they ship inside the IDE); the client id/secret/scopes and the
 * `/oauth-callback` loopback redirect are the IDE's published values.
 *
 * After the token exchange the account's Cloud Code project is discovered
 * (`loadCodeAssist`, then `onboardUser` for an account that never used it) and
 * the base URL that answered is pinned, so requests do not re-probe.
 */
class AntigravityOAuthManager(context: Context, instanceId: String) : OAuthManager(context, instanceId) {
    companion object {
        private const val TAG = "AntigravityOAuth"

        /** True only when this APK was built with the Antigravity OAuth client id and secret. */
        val isAvailableInThisBuild: Boolean get() = BuildConfig.ANTIGRAVITY_OAUTH_AVAILABLE

        /** Fails closed before any login side effect when the sign-in is compiled out of this build. */
        internal fun requireAvailable(available: Boolean = BuildConfig.ANTIGRAVITY_OAUTH_AVAILABLE) {
            if (!available) throw ProviderCustomizationUnavailableException()
        }
        private const val USERINFO_URL = "https://www.googleapis.com/oauth2/v2/userinfo?alt=json"
        private const val ONBOARD_POLLS = 24
        private const val ONBOARD_POLL_MS = 5_000L
        private const val LOGIN_TIMEOUT_MS = 5 * 60_000L

        /**
         * Sign in with Google in the system browser and store the access token as
         * the instance's credential, as the other OAuth providers do. Returns the
         * token; throws when the sign-in is refused, cancelled or times out.
         */
        suspend fun login(
            context: Context,
            instanceId: String,
            providerRepository: com.openminis.app.data.repository.ProviderRepository,
        ): String {
            // Before anything is started: no callback server, no browser, no network when the sign-in is compiled out.
            requireAvailable()
            val manager = AntigravityOAuthManager(context, instanceId)
            val done = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val signedIn = try {
                manager.startLogin { done.complete(it) }
                kotlinx.coroutines.withTimeout(LOGIN_TIMEOUT_MS) { done.await() }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                manager.cancelLogin()
                throw Exception("Google sign-in timed out. Try again.")
            } catch (e: kotlinx.coroutines.CancellationException) {
                manager.cancelLogin()
                throw e
            }
            if (!signedIn) throw Exception("Google sign-in failed. Check the network and try again.")
            val token = manager.validAccessToken() ?: throw Exception("Google sign-in returned no token.")
            providerRepository.saveApiKey(instanceId, token)
            return token
        }
    }

    override val authURL = "https://accounts.google.com/o/oauth2/v2/auth"
    override val tokenURL = "https://oauth2.googleapis.com/token"
    // Injected at build time from the private provider-customization.properties; see [isAvailableInThisBuild].
    override val clientId: String get() = requireAvailable().let { BuildConfig.ANTIGRAVITY_OAUTH_CLIENT_ID }
    override val clientSecret: String get() = requireAvailable().let { BuildConfig.ANTIGRAVITY_OAUTH_CLIENT_SECRET }
    override val callbackPort = 51121
    override val redirectPath = "/oauth-callback"
    override val scopes = listOf(
        "https://www.googleapis.com/auth/cloud-platform",
        "https://www.googleapis.com/auth/userinfo.email",
        "https://www.googleapis.com/auth/userinfo.profile",
        "https://www.googleapis.com/auth/cclog",
        "https://www.googleapis.com/auth/experimentsandconfigs",
    ).joinToString(" ")

    // Google only returns a refresh token for `access_type=offline` + `prompt=consent`.
    override val extraAuthorizationParams = listOf("access_type=offline", "prompt=consent")

    /** Cached Google account email, for the provider page. */
    var email: String?
        get() = loadOAuthString("email")
        private set(value) { value?.let { saveOAuthString("email", it) } }

    /** Cloud Code project the account is served from. */
    var gcpProjectId: String?
        get() = loadOAuthString("gcp_project")
        private set(value) { value?.let { saveOAuthString("gcp_project", it) } }

    /** Base URL that answered during discovery; requests are pinned to it. */
    var activeBaseURL: String?
        get() = loadOAuthString("base_url")
        private set(value) { value?.let { saveOAuthString("base_url", it) } }

    override suspend fun onTokensReceived(json: JSONObject) {
        val token = json.optString("access_token").ifEmpty { return }
        fetchEmail(token)
        discoverProject(token)
    }

    /**
     * Discover the project on demand: the login may have finished while offline
     * or the account may have needed onboarding, so a chat request retries it.
     */
    suspend fun discoverProjectIfNeeded(): Boolean {
        if (!gcpProjectId.isNullOrEmpty() && !activeBaseURL.isNullOrEmpty()) return true
        val token = validAccessToken() ?: return false
        return discoverProject(token)
    }

    private suspend fun fetchEmail(token: String) = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(USERINFO_URL)
                .header("Authorization", "Bearer $token")
                .header("User-Agent", AntigravityWire.USER_AGENT)
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    JSONObject(response.body?.string().orEmpty()).optString("email")
                        .takeIf { it.isNotEmpty() }?.let { email = it }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch email: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun discoverProject(token: String): Boolean = withContext(Dispatchers.IO) {
        var tierId = "free-tier"
        for (baseUrl in AntigravityWire.BASE_URLS) {
            val load = post(baseUrl, "loadCodeAssist", token, AntigravityWire.loadCodeAssistBody()) ?: continue
            val project = AntigravityWire.projectIdFrom(load)
            if (project != null) {
                gcpProjectId = project
                activeBaseURL = baseUrl
                Log.i(TAG, "Cloud Code project discovered via $baseUrl")
                return@withContext true
            }
            tierId = AntigravityWire.defaultTierId(load)
        }
        // The account has no project yet: onboard it, then poll the operation.
        for (baseUrl in AntigravityWire.BASE_URLS) {
            val project = onboard(baseUrl, token, tierId) ?: continue
            gcpProjectId = project
            activeBaseURL = baseUrl
            Log.i(TAG, "Cloud Code project onboarded via $baseUrl")
            return@withContext true
        }
        Log.w(TAG, "Cloud Code project discovery failed on every base URL")
        false
    }

    private suspend fun onboard(baseUrl: String, token: String, tierId: String): String? {
        val started = post(baseUrl, "onboardUser", token, AntigravityWire.onboardUserBody(tierId)) ?: return null
        AntigravityWire.projectIdFrom(started.optJSONObject("response"))?.let { return it }
        val operation = started.optString("name").takeIf { "operations/" in it } ?: return null
        repeat(ONBOARD_POLLS) {
            delay(ONBOARD_POLL_MS)
            val poll = get(baseUrl, "v1internal/$operation", token) ?: return@repeat
            if (poll.optBoolean("done", false)) {
                return AntigravityWire.projectIdFrom(poll.optJSONObject("response"))
            }
        }
        return null
    }

    private fun post(baseUrl: String, method: String, token: String, body: JSONObject): JSONObject? =
        call(
            Request.Builder().url("${baseUrl.trimEnd('/')}/v1internal:$method")
                .post(body.toString().toRequestBody("application/json".toMediaType())),
            token,
        )

    private fun get(baseUrl: String, path: String, token: String): JSONObject? =
        call(Request.Builder().url("${baseUrl.trimEnd('/')}/$path").get(), token)

    private fun call(builder: Request.Builder, token: String): JSONObject? = try {
        val request = builder
            .header("Authorization", "Bearer $token")
            .header("User-Agent", AntigravityWire.USER_AGENT)
            .header("Accept", "application/json")
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) JSONObject(response.body?.string().orEmpty()) else {
                Log.d(TAG, "${request.url.encodedPath} -> ${response.code}")
                null
            }
        }
    } catch (e: Exception) {
        Log.d(TAG, "Cloud Code call failed: ${e.javaClass.simpleName}")
        null
    }
}
