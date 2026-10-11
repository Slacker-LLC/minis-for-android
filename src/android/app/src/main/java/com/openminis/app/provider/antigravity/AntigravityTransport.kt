package com.openminis.app.provider.antigravity

import com.openminis.app.auth.AntigravityOAuthManager
import com.openminis.app.data.model.LLMError
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.UUID

/**
 * Where and how [com.openminis.app.provider.gemini.GeminiProvider] talks to
 * Cloud Code when it serves an Antigravity instance: token and project come
 * from the account's [AntigravityOAuthManager] (refresh-aware, resolved per
 * request), the body is wrapped by [AntigravityWire].
 */
class AntigravityTransport(private val oauth: AntigravityOAuthManager) {
    /** Stable for the lifetime of the provider, like the IDE's conversation id. */
    private val sessionId = "-" + (UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE)

    private class Session(val token: String, val projectId: String, val baseUrl: String)

    private suspend fun session(): Session {
        val token = oauth.validAccessToken()?.takeIf { it.isNotEmpty() } ?: throw LLMError.InvalidApiKey()
        if (!oauth.discoverProjectIfNeeded()) {
            throw LLMError.ProviderError(
                "Antigravity: no Cloud Code project for this Google account yet. " +
                    "Sign in again from the provider page, or try again later.",
            )
        }
        return Session(
            token = token,
            projectId = oauth.gcpProjectId.orEmpty(),
            baseUrl = oauth.activeBaseURL?.takeIf { it.isNotEmpty() } ?: AntigravityWire.BASE_URLS.first(),
        )
    }

    /** The wrapped, authenticated `generateContent` / `streamGenerateContent` request. */
    suspend fun buildRequest(
        modelId: String,
        inner: JSONObject,
        streaming: Boolean,
        thinkingEnabled: Boolean,
    ): Request {
        val session = session()
        val body = AntigravityWire.wrap(modelId, session.projectId, sessionId, inner)
        val path = if (streaming) "streamGenerateContent?alt=sse" else "generateContent"
        val builder = Request.Builder()
            .url("${session.baseUrl.trimEnd('/')}/v1internal:$path")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Authorization", "Bearer ${session.token}")
            .header("User-Agent", AntigravityWire.USER_AGENT)
            .header("Accept", if (streaming) "text/event-stream" else "application/json")
        if (AntigravityWire.needsInterleavedThinking(modelId, thinkingEnabled)) {
            builder.header("anthropic-beta", "interleaved-thinking-2025-05-14")
        }
        return builder.build()
    }
}
