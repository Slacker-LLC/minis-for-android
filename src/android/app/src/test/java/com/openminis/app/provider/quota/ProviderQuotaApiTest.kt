package com.openminis.app.provider.quota

import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bodies are the examples of each service's own documentation (see docs/development/PROVIDER-QUOTA.md). */
class ProviderQuotaApiTest {
    private fun instance(
        type: ProviderType = ProviderType.openAI,
        credential: ProviderCredential = ProviderCredential.apiKey,
        base: String? = null,
    ) = ProviderInstance(id = "p", label = "p", providerType = type, credentialType = credential, customBaseURL = base)

    @Test
    fun `each service is recognised by its exact host, never by a substring`() {
        assertEquals(QuotaKind.DEEPSEEK, ProviderQuotaApi.detect(instance(base = "https://api.deepseek.com")))
        assertEquals(QuotaKind.DEEPSEEK, ProviderQuotaApi.detect(instance(base = "https://api.deepseek.com/v1")))
        assertEquals(QuotaKind.MOONSHOT, ProviderQuotaApi.detect(instance(base = "https://api.moonshot.cn/v1")))
        assertEquals(QuotaKind.SILICONFLOW, ProviderQuotaApi.detect(instance(base = "https://api.siliconflow.cn/v1")))
        assertEquals(QuotaKind.OPENROUTER, ProviderQuotaApi.detect(instance(type = ProviderType.openRouter)))
        assertEquals(QuotaKind.CHATGPT, ProviderQuotaApi.detect(instance(credential = ProviderCredential.oauth)))
        // A look-alike host must never receive the key.
        assertNull(ProviderQuotaApi.detect(instance(base = "https://api.deepseek.com.evil.example/v1")))
        assertNull(ProviderQuotaApi.detect(instance(base = "https://evil.example/api.deepseek.com")))
        assertNull(ProviderQuotaApi.detect(instance(base = "https://relay.example/v1")))
        assertNull(ProviderQuotaApi.detect(instance()))
        // A pasted bearer is not a ChatGPT session.
        assertNull(ProviderQuotaApi.detect(instance(credential = ProviderCredential.oauth), manualBearer = true))
    }

    @Test
    fun `the key goes to the service's own endpoint only`() {
        val deepseek = ProviderQuotaApi.request(QuotaKind.DEEPSEEK, instance(base = "https://api.deepseek.com/v1"), "k")
        assertEquals("https://api.deepseek.com/user/balance", deepseek.url.toString())
        assertEquals("Bearer k", deepseek.header("Authorization"))
        val moonshot = ProviderQuotaApi.request(QuotaKind.MOONSHOT, instance(base = "https://api.moonshot.cn/v1"), "k")
        assertEquals("https://api.moonshot.cn/v1/users/me/balance", moonshot.url.toString())
        val silicon = ProviderQuotaApi.request(QuotaKind.SILICONFLOW, instance(base = "https://api.siliconflow.cn/v1"), "k")
        assertEquals("https://api.siliconflow.cn/v1/user/info", silicon.url.toString())
        val chatgpt = ProviderQuotaApi.request(QuotaKind.CHATGPT, instance(credential = ProviderCredential.oauth), "tok", "acct")
        assertEquals("https://chatgpt.com/backend-api/wham/usage", chatgpt.url.toString())
        assertEquals("acct", chatgpt.header("ChatGPT-Account-Id"))
        assertEquals("https://openrouter.ai/api/v1/key", ProviderQuotaApi.openRouterKeyRequest("k").url.toString())
    }

    @Test
    fun `deepseek balance with two currencies`() {
        val quota = ProviderQuotaApi.parse(
            QuotaKind.DEEPSEEK,
            """{"is_available":true,"balance_infos":[{"currency":"CNY","total_balance":"110.00","granted_balance":"10.00","topped_up_balance":"100.00"},
               {"currency":"USD","total_balance":"0.00","granted_balance":"0.00","topped_up_balance":"0.00"}]}""",
            1L,
        )!!
        assertEquals(listOf("CNY", "USD"), quota.balances.map { it.currency })
        assertEquals(110.0, quota.balances[0].total, 0.0)
        assertEquals(100.0, quota.balances[0].toppedUp!!, 0.0)
        // An empty balance in one currency does not hide the other one that still has money.
        assertEquals(QuotaLevel.OK, quota.level)
    }

    @Test
    fun `moonshot siliconflow and openrouter`() {
        val moonshot = ProviderQuotaApi.parse(
            QuotaKind.MOONSHOT,
            """{"code":0,"data":{"available_balance":49.58894,"voucher_balance":46.58893,"cash_balance":3.00001},"scode":"0x0","status":true}""", 1L,
        )!!
        assertEquals(49.58894, moonshot.balances.single().total, 1e-9)
        val silicon = ProviderQuotaApi.parse(
            QuotaKind.SILICONFLOW,
            """{"code":20000,"message":"OK","status":true,"data":{"id":"","name":"x","balance":"0.88","status":"normal","chargeBalance":"88.00","totalBalance":"88.88"}}""", 1L,
        )!!
        assertEquals(88.88, silicon.balances.single().total, 1e-9)
        val credits = ProviderQuotaApi.parse(QuotaKind.OPENROUTER, """{"data":{"total_credits":100.5,"total_usage":25.75}}""", 1L)!!
        assertEquals(74.75, credits.balances.single().total, 1e-9)
        val key = ProviderQuotaApi.parse(QuotaKind.OPENROUTER, """{"data":{"label":"k","limit":10,"limit_remaining":3.5,"usage":6.5}}""", 1L)!!
        assertEquals(3.5, key.balances.single().total, 1e-9)
        assertEquals(QuotaLevel.OK, ProviderQuota(balances = listOf(Balance("USD", 3.5))).level)
        // A key without a spending limit has no number to show.
        assertNull(ProviderQuotaApi.parse(QuotaKind.OPENROUTER, """{"data":{"label":"k","limit":null,"limit_remaining":null,"usage":6.5}}""", 1L))
    }

    @Test
    fun `chatgpt subscription windows`() {
        val quota = ProviderQuotaApi.parse(
            QuotaKind.CHATGPT,
            """{"plan_type":"plus","rate_limit":{"allowed":true,"limit_reached":false,
               "primary_window":{"used_percent":42,"limit_window_seconds":18000,"reset_after_seconds":600,"reset_at":1800000000},
               "secondary_window":{"used_percent":91,"limit_window_seconds":604800,"reset_after_seconds":86400,"reset_at":1800100000}}}""",
            1L,
        )!!
        assertEquals("plus", quota.plan)
        assertEquals(listOf("5h", "7d"), quota.windows.map { it.label })
        assertEquals(listOf(42, 91), quota.windows.map { it.usedPercent })
        assertEquals(QuotaLevel.LOW, quota.level)
    }

    @Test
    fun `levels`() {
        assertEquals(QuotaLevel.EMPTY, ProviderQuota(balances = listOf(Balance("CNY", 0.0))).level)
        assertEquals(QuotaLevel.EMPTY, ProviderQuota(balances = listOf(Balance("CNY", 20.0)), available = false).level)
        assertEquals(QuotaLevel.EMPTY, ProviderQuota(windows = listOf(UsageWindow("5h", 100))).level)
        assertEquals(QuotaLevel.LOW, ProviderQuota(balances = listOf(Balance("CNY", 4.9))).level)
        assertEquals(QuotaLevel.OK, ProviderQuota(balances = listOf(Balance("CNY", 5.1))).level)
        assertEquals(QuotaLevel.LOW, ProviderQuota(balances = listOf(Balance("USD", 0.5))).level)
        assertEquals(QuotaLevel.OK, ProviderQuota(balances = listOf(Balance("CNY", 0.0), Balance("USD", 20.0))).level)
    }

    @Test
    fun `a body that is not what the service documents is refused, not guessed at`() {
        assertNull(ProviderQuotaApi.parse(QuotaKind.DEEPSEEK, "{}", 1L))
        assertNull(ProviderQuotaApi.parse(QuotaKind.DEEPSEEK, "not json", 1L))
        assertNull(ProviderQuotaApi.parse(QuotaKind.MOONSHOT, """{"data":{}}""", 1L))
        assertNull(ProviderQuotaApi.parse(QuotaKind.SILICONFLOW, """{"data":{"balance":"x"}}""", 1L))
        assertNotNull(ProviderQuotaApi.parse(QuotaKind.CHATGPT, """{"plan_type":"free"}""", 1L))
        assertFalse(ProviderQuotaApi.windowLabel(0) == "5h")
        assertEquals("90m", ProviderQuotaApi.windowLabel(5400))
        assertTrue(ProviderQuotaApi.windowLabel(86_400) == "1d")
    }
}
