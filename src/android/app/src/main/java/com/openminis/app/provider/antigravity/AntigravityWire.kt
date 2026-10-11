package com.openminis.app.provider.antigravity

import org.json.JSONObject
import java.util.UUID

/**
 * Wire format of Google's Cloud Code Assist endpoint as the Antigravity client
 * speaks it: a Gemini `generateContent` body wrapped in an envelope, answered
 * with the Gemini response wrapped in `{"response": …}`.
 *
 * Pure functions only (no Android, no network), so the envelope rules can be
 * pinned by unit tests.
 */
object AntigravityWire {
    /**
     * Cloud Code refuses newer models for clients older than 2.9.0, so the
     * reported version must stay at or above that floor.
     */
    const val CLIENT_VERSION = "2.9.1"
    const val USER_AGENT = "antigravity/hub/$CLIENT_VERSION darwin/arm64"

    /** Candidate base URLs, probed in order on login; the winner is pinned. */
    val BASE_URLS = listOf(
        "https://daily-cloudcode-pa.googleapis.com",
        "https://daily-cloudcode-pa.sandbox.googleapis.com",
        "https://cloudcode-pa.googleapis.com",
    )

    fun isClaude(modelId: String): Boolean = modelId.contains("claude", ignoreCase = true)

    /** Claude "thinking" variants need the interleaved-thinking beta header. */
    fun needsInterleavedThinking(modelId: String, thinkingEnabled: Boolean): Boolean =
        isClaude(modelId) && (modelId.contains("thinking", ignoreCase = true) || thinkingEnabled)

    /**
     * Wrap a Gemini request body for Cloud Code. [inner] is edited in place for
     * the per-family rules the endpoint enforces:
     *  - `safetySettings` is dropped (the endpoint rejects it);
     *  - Claude: function calling is `VALIDATED` when tools are present and no
     *    `thinkingConfig` is sent (Claude thinks server-side);
     *  - everything else: `maxOutputTokens` is dropped (the endpoint caps it).
     */
    fun wrap(modelId: String, projectId: String, sessionId: String, inner: JSONObject): JSONObject {
        inner.remove("safetySettings")
        inner.put("sessionId", sessionId)
        val generationConfig = inner.optJSONObject("generationConfig")
        if (isClaude(modelId)) {
            generationConfig?.remove("thinkingConfig")
            if (inner.has("tools")) {
                val toolConfig = inner.optJSONObject("toolConfig") ?: JSONObject().also { inner.put("toolConfig", it) }
                val calling = toolConfig.optJSONObject("functionCallingConfig")
                    ?: JSONObject().also { toolConfig.put("functionCallingConfig", it) }
                calling.put("mode", "VALIDATED")
            }
        } else {
            generationConfig?.remove("maxOutputTokens")
        }
        return JSONObject().apply {
            put("model", modelId)
            put("userAgent", "antigravity")
            put("requestType", "agent")
            put("project", projectId)
            put("requestId", "agent-${UUID.randomUUID()}")
            put("request", inner)
        }
    }

    /** `{"response": {…}}` → the inner Gemini response; anything else passes through. */
    fun unwrap(json: JSONObject): JSONObject = json.optJSONObject("response") ?: json

    /** Body of `loadCodeAssist`: the IDE type is what selects the Antigravity tier. */
    fun loadCodeAssistBody(): JSONObject =
        JSONObject().put("metadata", JSONObject().put("ideType", "ANTIGRAVITY"))

    fun onboardUserBody(tierId: String): JSONObject = JSONObject()
        .put("tierId", tierId)
        .put(
            "metadata",
            JSONObject()
                .put("ide_type", "ANTIGRAVITY")
                .put("ide_version", CLIENT_VERSION)
                .put("ide_name", "antigravity"),
        )

    /**
     * Project id from a `loadCodeAssist` / `onboardUser` answer. The field comes
     * back as `cloudaicompanionProject` (a string, or an object with `id`),
     * `projectId`, or `project` (possibly `projects/<id>`).
     */
    fun projectIdFrom(json: JSONObject?): String? {
        if (json == null) return null
        for (key in listOf("cloudaicompanionProject", "projectId", "project")) {
            when (val value = json.opt(key)) {
                is String -> value.trim().takeIf { it.isNotEmpty() }?.let { return it.substringAfterLast('/') }
                is JSONObject -> value.optString("id").trim().takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        return null
    }

    /** Tier to onboard with: the default allowed tier, else the current one, else free. */
    fun defaultTierId(loadResponse: JSONObject): String {
        val tiers = loadResponse.optJSONArray("allowedTiers")
        if (tiers != null) {
            for (i in 0 until tiers.length()) {
                val tier = tiers.optJSONObject(i) ?: continue
                if (tier.optBoolean("isDefault", false)) {
                    tier.optString("id").trim().takeIf { it.isNotEmpty() }?.let { return it }
                }
            }
        }
        loadResponse.optJSONObject("currentTier")?.optString("id")?.trim()
            ?.takeIf { it.isNotEmpty() }?.let { return it }
        return "free-tier"
    }
}
