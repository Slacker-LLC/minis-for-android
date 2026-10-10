package com.openminis.app.provider.bridge

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProviderConfigsTest {
    private fun p(
        id: String,
        type: ProviderType = ProviderType.openAI,
        base: String? = "https://relay.example/v1",
        credential: ProviderCredential = ProviderCredential.apiKey,
        enabled: Boolean = true,
        appendV1: Boolean = false,
    ) = ProviderInstance(
        id = id, label = id, providerType = type, credentialType = credential, isEnabled = enabled,
        customBaseURL = base, appendV1Suffix = appendV1,
    )

    private fun m(instance: String, vararg ids: String) =
        ids.map { ModelEntry(providerInstanceId = instance, baseModel = LLMModel(id = it, displayName = it, provider = instance)) }

    private fun collect(vararg instances: ProviderInstance, entries: List<ModelEntry>, keys: Map<String, String?> = instances.associate { it.id to "secret-${it.id}" }) =
        AgentProviderConfigs.collect(instances.toList(), entries) { keys[it] }

    @Test
    fun `a relay with a key and models becomes a provider, a service without a base of its own does not`() {
        val out = collect(p("relay"), p("official", base = null), entries = m("relay", "gpt-x", "gpt-y") + m("official", "gpt-4"))
        assertEquals(listOf("minis-relay"), out.map { it.id })
        assertEquals("MINIS_KEY_RELAY", out[0].envName)
        assertEquals("https://relay.example/v1", out[0].baseUrl)
        assertEquals(listOf("gpt-x", "gpt-y"), out[0].models)
    }

    @Test
    fun `sign-ins, disabled, keyless, remote http and model-less providers are left out`() {
        val out = collect(
            p("oauth", credential = ProviderCredential.oauth), p("off", enabled = false), p("nokey"),
            p("plain", base = "http://relay.example/v1"), p("empty"), p("gem", type = ProviderType.gemini),
            entries = m("oauth", "a") + m("off", "a") + m("nokey", "a") + m("plain", "a") + m("gem", "a"),
            keys = mapOf("oauth" to "t", "off" to "k", "nokey" to " ", "plain" to "k", "empty" to "k", "gem" to "k"),
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `a loopback http server is allowed, an anthropic one is given as a root without v1`() {
        val out = collect(
            p("ollama", base = "http://127.0.0.1:11434/v1"),
            p("claude", type = ProviderType.anthropic, base = "https://relay.example/anthropic", appendV1 = true),
            entries = m("ollama", "llama3") + m("claude", "sonnet"),
        )
        assertEquals("http://127.0.0.1:11434/v1", out[0].baseUrl)
        assertTrue(out[1].anthropic)
        assertEquals("https://relay.example/anthropic", out[1].baseUrl)
    }

    @Test
    fun `labels that collide or start with a digit still give distinct valid variable names`() {
        val a = p("a").copy(label = "302 AI")
        val b = p("b").copy(label = "302 AI")
        val out = collect(a, b, entries = m("a", "x") + m("b", "x"))
        assertEquals(2, out.map { it.envName }.toSet().size)
        out.forEach { assertTrue(it.envName, Regex("[A-Z_][A-Z0-9_]*").matches(it.envName)) }
    }

    @Test
    fun `no config text holds a key, they name the variable instead`() {
        val providers = collect(p("relay"), p("claude", type = ProviderType.anthropic, base = "https://r.example"), entries = m("relay", "a") + m("claude", "b"))
        val env = AgentProviderConfigs.env(providers)
        assertEquals("secret-relay", env["MINIS_KEY_RELAY"])
        val texts = listOf(
            env.getValue(AgentProviderConfigs.OPENCODE_ENV),
            AgentProviderConfigs.piModelsJson(null, providers)!!,
            AgentProviderConfigs.commandCodeProvidersJson(null, providers)!!,
        )
        texts.forEach { assertFalse(it.contains("secret-")) }
        assertTrue(texts[0].contains("{env:MINIS_KEY_RELAY}"))
        assertTrue(texts[1].contains("\$MINIS_KEY_RELAY"))
        assertTrue(texts[2].contains("\$MINIS_KEY_RELAY"))
    }

    @Test
    fun `each agent gets its own shape`() {
        val providers = collect(p("relay"), p("claude", type = ProviderType.anthropic, base = "https://r.example/anthropic"), entries = m("relay", "a") + m("claude", "b"))
        val open = JSONObject(AgentProviderConfigs.env(providers).getValue(AgentProviderConfigs.OPENCODE_ENV)).getJSONObject("provider")
        assertEquals("@ai-sdk/openai-compatible", open.getJSONObject("minis-relay").getString("npm"))
        assertEquals("@ai-sdk/anthropic", open.getJSONObject("minis-claude").getString("npm"))
        assertEquals("https://r.example/anthropic/v1", open.getJSONObject("minis-claude").getJSONObject("options").getString("baseURL"))
        val pi = JSONObject(AgentProviderConfigs.piModelsJson(null, providers)!!).getJSONObject("providers")
        assertEquals("openai-completions", pi.getJSONObject("minis-relay").getString("api"))
        assertEquals("anthropic-messages", pi.getJSONObject("minis-claude").getString("api"))
        assertEquals("a", pi.getJSONObject("minis-relay").getJSONArray("models").getJSONObject(0).getString("id"))
        val cc = JSONObject(AgentProviderConfigs.commandCodeProvidersJson(null, providers)!!).getJSONObject("provider")
        assertNull(cc.getJSONObject("minis-relay").optString("api", "").takeIf { it.isNotEmpty() })
        assertEquals("anthropic-messages", cc.getJSONObject("minis-claude").getString("api"))
        assertNotNull(cc.getJSONObject("minis-relay").getJSONObject("models").optJSONObject("a"))
    }

    @Test
    fun `the user's own entries and other settings survive, ours are replaced and removed`() {
        val existing = """{"theme":"dark","provider":{"mine":{"baseURL":"https://mine.example"},"minis-old":{"baseURL":"https://old.example"}}}"""
        val providers = collect(p("relay"), entries = m("relay", "a"))
        val written = JSONObject(AgentProviderConfigs.commandCodeProvidersJson(existing, providers)!!)
        assertEquals("dark", written.getString("theme"))
        val section = written.getJSONObject("provider")
        assertTrue(section.has("mine")); assertTrue(section.has("minis-relay")); assertFalse(section.has("minis-old"))
        val removed = JSONObject(AgentProviderConfigs.commandCodeProvidersJson(written.toString(), emptyList())!!)
        assertEquals(listOf("mine"), removed.getJSONObject("provider").keys().asSequence().toList())
    }

    @Test
    fun `a file that only held our entries is deleted, an unreadable one is never rewritten`() {
        val providers = collect(p("relay"), entries = m("relay", "a"))
        val ours = AgentProviderConfigs.piModelsJson(null, providers)!!
        assertEquals("", AgentProviderConfigs.piModelsJson(ours, emptyList()))
        assertNull(AgentProviderConfigs.piModelsJson("{ not json", providers))
        assertNull(AgentProviderConfigs.commandCodeProvidersJson("[1,2]", emptyList()))
    }
}
