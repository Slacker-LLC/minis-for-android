package com.openminis.app.provider.bridge

import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * The providers that have a base URL of their own (relays, the services in "Add provider") written the way each coding
 * agent in the sandbox wants a custom endpoint:
 *
 *  - pi: `~/.pi/agent/models.json` - `providers.<id>.{baseUrl, api, apiKey: "$NAME", models: [{id}]}` (docs/models.md,
 *    "Configure a compatible endpoint").
 *  - Command Code: `~/.commandcode/providers.json` - `provider.<id>.{baseURL, api, apiKey: "$NAME", models: {id: {}}}`
 *    (commandcode.ai/docs/byok).
 *  - OpenCode: the `OPENCODE_CONFIG_CONTENT` variable (inline JSON config, opencode.ai/docs/cli) - `provider.<id>.{npm,
 *    options.{baseURL, apiKey: "{env:NAME}"}, models}` (opencode.ai/docs/providers).
 *
 * No file ever holds a key: each provider's key is exported as its own variable ([CustomProvider.envName]) and the files
 * name that variable. Entries this app writes are told apart by the [ID_PREFIX]; everything else in those files is left alone.
 */
object AgentProviderConfigs {
    const val ID_PREFIX = "minis-"
    const val OPENCODE_ENV = "OPENCODE_CONFIG_CONTENT"
    private const val MAX_MODELS = 80

    data class CustomProvider(
        val id: String,
        val label: String,
        /** Anthropic Messages format (root URL, the agent adds `/v1/messages`) instead of OpenAI Chat Completions. */
        val anthropic: Boolean,
        /** OpenAI format: the complete base (`.../v1`); Anthropic format: the root without `/v1`. */
        val baseUrl: String,
        val envName: String,
        val key: String,
        val models: List<String>,
    )

    /** One entry per enabled API-key provider with its own https (or loopback http) base URL, a key and at least one model. */
    internal fun collect(
        instances: List<ProviderInstance>,
        entries: List<ModelEntry>,
        keyOf: (String) -> String?,
    ): List<CustomProvider> {
        val used = HashSet<String>()
        val out = ArrayList<CustomProvider>()
        for (instance in instances) {
            if (!instance.isEnabled || instance.credentialType != ProviderCredential.apiKey) continue
            val anthropic = when (instance.providerType) {
                ProviderType.anthropic -> true
                ProviderType.openAI, ProviderType.openAIResponses -> false
                else -> continue
            }
            val custom = instance.customBaseURL?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val url = custom.toHttpUrlOrNull() ?: continue
            if (!(url.isHttps || url.host == "127.0.0.1" || url.host == "localhost")) continue
            val base = if (anthropic) ProviderBridge.anthropicRoot(custom) ?: continue else instance.effectiveBaseURL ?: continue
            val key = keyOf(instance.id)?.trim()?.takeIf { it.isNotEmpty() && it.none { c -> c == '\n' || c == '\r' || c == '\u0000' } } ?: continue
            val models = entries.asSequence()
                .filter { it.providerInstanceId == instance.id && !it.isHidden }
                .map { it.baseModel.id }
                .filter { it.isNotBlank() && it.length <= 200 }
                .distinct().take(MAX_MODELS).toList()
            if (models.isEmpty()) continue
            val slug = slug(instance.label.ifBlank { url.host }, used)
            out += CustomProvider(
                id = ID_PREFIX + slug,
                label = instance.label.ifBlank { url.host },
                anthropic = anthropic,
                baseUrl = base,
                envName = "MINIS_KEY_" + slug.uppercase().replace('-', '_'),
                key = key,
                models = models,
            )
        }
        return out
    }

    private fun slug(label: String, used: MutableSet<String>): String {
        val base = label.lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '-' }.joinToString("")
            .replace(Regex("-+"), "-").trim('-').take(24).ifEmpty { "provider" }
        var candidate = base
        var n = 2
        // A name that starts with a digit is no shell identifier once it is upper-cased into a variable name.
        if (candidate.first().isDigit()) candidate = "p-$candidate"
        while (!used.add(candidate)) candidate = "$base-${n++}"
        return candidate
    }

    /** The variables: one key per provider, plus the inline OpenCode config that names them. */
    internal fun env(providers: List<CustomProvider>): Map<String, String> {
        if (providers.isEmpty()) return emptyMap()
        val out = linkedMapOf<String, String>()
        providers.forEach { out[it.envName] = it.key }
        out[OPENCODE_ENV] = openCodeConfig(providers).toString()
        return out
    }

    internal fun openCodeConfig(providers: List<CustomProvider>): JSONObject {
        val provider = JSONObject()
        for (p in providers) {
            val models = JSONObject()
            p.models.forEach { models.put(it, JSONObject().put("name", it)) }
            provider.put(
                p.id,
                JSONObject()
                    .put("npm", if (p.anthropic) "@ai-sdk/anthropic" else "@ai-sdk/openai-compatible")
                    .put("name", p.label)
                    .put(
                        "options",
                        JSONObject()
                            .put("baseURL", if (p.anthropic) p.baseUrl + "/v1" else p.baseUrl)
                            .put("apiKey", "{env:${p.envName}}"),
                    )
                    .put("models", models),
            )
        }
        return JSONObject().put("\$schema", "https://opencode.ai/config.json").put("provider", provider)
    }

    private fun piEntries(providers: List<CustomProvider>): JSONObject {
        val out = JSONObject()
        for (p in providers) {
            val models = JSONArray()
            p.models.forEach { models.put(JSONObject().put("id", it)) }
            out.put(
                p.id,
                JSONObject()
                    .put("baseUrl", p.baseUrl)
                    .put("api", if (p.anthropic) "anthropic-messages" else "openai-completions")
                    .put("apiKey", "\$${p.envName}")
                    .put("models", models),
            )
        }
        return out
    }

    private fun commandCodeEntries(providers: List<CustomProvider>): JSONObject {
        val out = JSONObject()
        for (p in providers) {
            val models = JSONObject()
            p.models.forEach { models.put(it, JSONObject()) }
            val entry = JSONObject().put("name", p.label).put("baseURL", p.baseUrl).put("apiKey", "\$${p.envName}")
            if (p.anthropic) entry.put("api", "anthropic-messages")
            out.put(p.id, entry.put("models", models))
        }
        return out
    }

    /** pi's `models.json` text with this app's entries replaced by [providers]; null when [existing] is not JSON this code can vouch for. */
    internal fun piModelsJson(existing: String?, providers: List<CustomProvider>): String? =
        merge(existing, "providers", if (providers.isEmpty()) null else piEntries(providers))

    /** Command Code's `providers.json` text, same rule. */
    internal fun commandCodeProvidersJson(existing: String?, providers: List<CustomProvider>): String? =
        merge(existing, "provider", if (providers.isEmpty()) null else commandCodeEntries(providers))

    /**
     * [existing] with every `$topKey` entry whose id starts with [ID_PREFIX] replaced by [ours] (or only removed when null).
     * Returns "" when nothing but this app's entries was in the file (the caller deletes it), the new text otherwise, and
     * null when [existing] does not parse as a JSON object: such a file is never rewritten.
     */
    internal fun merge(existing: String?, topKey: String, ours: JSONObject?): String? {
        val root = if (existing.isNullOrBlank()) JSONObject() else runCatching { JSONObject(existing) }.getOrNull() ?: return null
        val section = root.optJSONObject(topKey) ?: JSONObject()
        val kept = JSONObject()
        for (name in section.keys().asSequence().toList()) if (!name.startsWith(ID_PREFIX)) kept.put(name, section.get(name))
        ours?.keys()?.forEach { kept.put(it, ours.get(it)) }
        root.remove(topKey)
        if (kept.length() > 0) root.put(topKey, kept)
        return if (root.length() == 0) "" else root.toString(2) + "\n"
    }
}
