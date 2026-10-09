package com.openminis.app.provider.bridge

import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderBridgeTest {
    private fun p(
        id: String,
        type: ProviderType,
        base: String? = null,
        credential: ProviderCredential = ProviderCredential.apiKey,
        enabled: Boolean = true,
    ) = ProviderInstance(id = id, label = id, providerType = type, credentialType = credential, isEnabled = enabled, customBaseURL = base)

    private fun env(vararg instances: ProviderInstance, keys: Map<String, String?> = instances.associate { it.id to "key-${it.id}" }) =
        ProviderBridge.envFor(instances.toList()) { keys[it] }

    @Test
    fun `each service gets the variable its agents read`() {
        val out = env(
            p("a", ProviderType.anthropic), p("o", ProviderType.openAI), p("g", ProviderType.gemini),
            p("r", ProviderType.openRouter), p("x", ProviderType.xAI),
            p("d", ProviderType.openAI, "https://api.deepseek.com"), p("m", ProviderType.openAI, "https://api.moonshot.cn/v1"),
        )
        assertEquals("key-a", out["ANTHROPIC_API_KEY"])
        assertEquals("key-o", out["OPENAI_API_KEY"])
        assertEquals("key-g", out["GEMINI_API_KEY"])
        assertEquals("key-r", out["OPENROUTER_API_KEY"])
        assertEquals("key-x", out["XAI_API_KEY"])
        assertEquals("key-d", out["DEEPSEEK_API_KEY"])
        assertEquals("key-m", out["MOONSHOT_API_KEY"])
        assertNull("official endpoints need no base URL", out["OPENAI_BASE_URL"])
    }

    @Test
    fun `a sign-in, a disabled provider and a provider without a key are left out`() {
        val out = env(
            p("oauth", ProviderType.openAI, credential = ProviderCredential.oauth),
            p("off", ProviderType.anthropic, enabled = false),
            p("nokey", ProviderType.gemini),
            keys = mapOf("oauth" to "token", "off" to "k", "nokey" to " "),
        )
        assertTrue(out.isEmpty())
    }

    @Test
    fun `the first provider of a service wins, an official OpenAI beats a relay, and a relay brings its own base`() {
        val relay = p("relay", ProviderType.openAI, "https://relay.example/v1")
        val official = p("official", ProviderType.openAI)
        assertEquals("key-official", env(relay, official)["OPENAI_API_KEY"])
        assertNull(env(relay, official)["OPENAI_BASE_URL"])
        val onlyRelay = env(relay)
        assertEquals("key-relay", onlyRelay["OPENAI_API_KEY"])
        assertEquals("https://relay.example/v1", onlyRelay["OPENAI_BASE_URL"])
        // A cleartext relay never gets exported.
        assertTrue(env(p("plain", ProviderType.openAI, "http://relay.example/v1")).isEmpty())
        assertEquals("key-first", env(p("first", ProviderType.gemini), p("second", ProviderType.gemini))["GEMINI_API_KEY"])
    }

    @Test
    fun `an Anthropic relay exports its origin with its key`() {
        val out = env(p("a", ProviderType.anthropic, "https://relay.example/v1/"))
        assertEquals("https://relay.example", out["ANTHROPIC_BASE_URL"])
        assertNull(ProviderBridge.anthropicBase("http://relay.example"))
        assertNull(ProviderBridge.anthropicBase(null))
    }

    @Test
    fun `a key that could break out of its line is refused`() {
        assertTrue(env(p("a", ProviderType.gemini), keys = mapOf("a" to "k\nexport EVIL=1")).isEmpty())
        assertEquals("export K='it'\\''s'\n", ProviderBridge.renderEnvFile(mapOf("K" to "it's")).lines().drop(1).joinToString("\n"))
    }

    @Test
    fun `the managed block is replaced in place and removed cleanly, and the user's own lines stay`() {
        val user = "export PATH=\"\$HOME/.local/bin:\$PATH\"\nalias ll='ls -l'\n"
        val once = ProviderBridge.withManagedBlock(user, ProviderBridge.BLOCK_BODY)
        assertTrue(once.startsWith(user.trimEnd('\n')))
        assertTrue(once.contains(ProviderBridge.BLOCK_BEGIN))
        assertEquals("applying twice changes nothing", once, ProviderBridge.withManagedBlock(once, ProviderBridge.BLOCK_BODY))
        assertEquals("removal restores the user's text", user, ProviderBridge.withManagedBlock(once, null))
        assertEquals("", ProviderBridge.withManagedBlock("", null))
        assertFalse(ProviderBridge.withManagedBlock("", ProviderBridge.BLOCK_BODY).isEmpty())
    }

    @Test
    fun `a known service is exported under the name its own tools read`() {
        val out = env(
            p("glm", ProviderType.openAI, "https://open.bigmodel.cn/api/paas/v4"),
            p("groq", ProviderType.openAI, "https://api.groq.com/openai/v1"),
            p("qwen", ProviderType.openAI, "https://coding.dashscope.aliyuncs.com/v1"),
            p("ark", ProviderType.openAI, "https://ark.cn-beijing.volces.com/api/coding/v3"),
            p("mini", ProviderType.anthropic, "https://api.minimax.io/anthropic"),
            p("kimi", ProviderType.kimiCode),
        )
        assertEquals("key-glm", out["ZHIPU_API_KEY"])
        assertEquals("key-groq", out["GROQ_API_KEY"])
        assertEquals("key-qwen", out["ALIBABA_CODING_PLAN_API_KEY"])
        assertEquals("key-ark", out["ARK_CODING_PLAN_API_KEY"])
        assertEquals("key-mini", out["MINIMAX_API_KEY"])
        assertEquals("key-kimi", out["KIMI_API_KEY"])
        assertEquals("https://api.kimi.com/coding/v1", out["KIMI_BASE_URL"])
        assertNull("known services are not relays", out["OPENAI_BASE_URL"])
    }

    @Test
    fun `a host that merely contains a known name is not that service`() {
        assertNull(ProviderBridge.serviceVariable("https://api.groq.com.evil.example/v1"))
        assertNull(ProviderBridge.serviceVariable("https://evil.example/api.groq.com"))
        assertNull(ProviderBridge.serviceVariable("not a url"))
    }
}
