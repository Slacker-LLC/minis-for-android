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
        // A look-alike host is not DeepSeek: it is a relay, and only ever gets asked on its own origin.
        assertEquals(QuotaKind.RELAY, ProviderQuotaApi.detect(instance(base = "https://api.deepseek.com.evil.example/v1")))
        assertEquals("https://api.deepseek.com.evil.example", ProviderQuotaApi.relayOrigin(instance(base = "https://api.deepseek.com.evil.example/v1")))
        assertEquals(QuotaKind.RELAY, ProviderQuotaApi.detect(instance(base = "https://evil.example/api.deepseek.com")))
        assertEquals(QuotaKind.RELAY, ProviderQuotaApi.detect(instance(base = "https://relay.example/v1")))
        assertNull("cleartext relays are never asked", ProviderQuotaApi.detect(instance(base = "http://relay.example/v1")))
        assertNull("the official endpoints have nothing to ask", ProviderQuotaApi.detect(instance(base = "https://api.openai.com/v1")))
        assertNull(ProviderQuotaApi.detect(instance()))
        // Sign-ins: each service's own endpoint; a pasted bearer is not a session.
        assertEquals(QuotaKind.CLAUDE, ProviderQuotaApi.detect(instance(type = ProviderType.anthropic, credential = ProviderCredential.oauth)))
        assertEquals(QuotaKind.KIMI_CODE, ProviderQuotaApi.detect(instance(type = ProviderType.kimiCode, credential = ProviderCredential.oauth)))
        assertNull(ProviderQuotaApi.detect(instance(credential = ProviderCredential.oauth), manualBearer = true))
        // No balance API is documented for Xiaomi MiMo: the page links to its console.
        assertEquals(QuotaKind.CONSOLE, ProviderQuotaApi.detect(instance(base = "https://token-plan-cn.xiaomimimo.com/v1")))
        assertNull(ProviderQuotaApi.consoleUrl(instance(base = "https://api.commandcode.ai/provider/v1")))
        assertEquals("https://platform.xiaomimimo.com", ProviderQuotaApi.consoleUrl(instance(base = "https://api.xiaomimimo.com/v1")))
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

    @Test
    fun `claude subscription windows`() {
        val quota = ProviderQuotaApi.parse(
            QuotaKind.CLAUDE,
            """{"five_hour":{"utilization":37.4,"resets_at":"2026-10-10T08:00:00.000000+00:00"},
               "seven_day":{"utilization":81.0,"resets_at":"2026-10-14T00:00:00Z"},
               "seven_day_sonnet":{"utilization":12.0,"resets_at":null},"seven_day_opus":null}""",
            1L,
        )!!
        assertEquals(listOf("5h", "7d", "7d Sonnet"), quota.windows.map { it.label })
        assertEquals(listOf(37, 81, 12), quota.windows.map { it.usedPercent })
        assertEquals(1_791_619_200L, quota.windows[0].resetAtEpochSec)
        assertNull(quota.windows[2].resetAtEpochSec)
        val request = ProviderQuotaApi.request(QuotaKind.CLAUDE, instance(type = ProviderType.anthropic, credential = ProviderCredential.oauth), "tok")
        assertEquals("https://api.anthropic.com/api/oauth/usage", request.url.toString())
        assertEquals("oauth-2025-04-20", request.header("anthropic-beta"))
        assertNull(ProviderQuotaApi.parse(QuotaKind.CLAUDE, "{}", 1L))
    }

    @Test
    fun `kimi code weekly usage and limits`() {
        val quota = ProviderQuotaApi.parse(
            QuotaKind.KIMI_CODE,
            """{"usage":{"limit":"100","remaining":"40","resetTime":"2026-10-14T00:00:00Z"},
               "limits":[{"detail":{"limit":"200","used":"50","name":"5h rolling"},"window":{"duration":300,"timeUnit":"TIME_UNIT_MINUTE"}},
                         {"detail":{"limit":10,"used":10},"window":{"duration":1,"timeUnit":"TIME_UNIT_DAY"}}]}""",
            1L,
        )!!
        assertEquals(listOf("weekly", "5h rolling", "1d"), quota.windows.map { it.label })
        assertEquals(listOf(60, 25, 100), quota.windows.map { it.usedPercent })
        assertEquals(QuotaLevel.EMPTY, quota.level)
        assertEquals("https://api.kimi.com/coding/v1/usages", ProviderQuotaApi.request(QuotaKind.KIMI_CODE, instance(type = ProviderType.kimiCode, credential = ProviderCredential.oauth), "t").url.toString())
        assertNull(ProviderQuotaApi.parse(QuotaKind.KIMI_CODE, """{"usage":{}}""", 1L))
    }

    @Test
    fun `sub2api key introspection`() {
        val wallet = ProviderQuotaApi.parseSub2Api("""{"mode":"unrestricted","isValid":true,"planName":"wallet","remaining":12.5,"unit":"USD","balance":12.5}""", 1L)!!
        assertEquals(12.5, wallet.balances.single().total, 0.0)
        assertEquals("USD", wallet.balances.single().currency)
        assertEquals(true, wallet.available)
        val limited = ProviderQuotaApi.parseSub2Api(
            """{"mode":"quota_limited","isValid":true,"quota":{"limit":10,"used":4,"remaining":6,"unit":"USD"},"remaining":6,"unit":"USD",
               "rate_limits":[{"window":"5h","limit":2,"used":0.5,"remaining":1.5,"reset_at":"2026-10-10T08:00:00Z"}]}""", 1L,
        )!!
        assertEquals(6.0, limited.balances.single().total, 0.0)
        assertEquals(75, 100 - limited.windows.single().usedPercent)
        assertEquals(false, ProviderQuotaApi.parseSub2Api("""{"isValid":false,"remaining":3,"unit":"USD"}""", 1L)!!.available)
        assertEquals(QuotaLevel.EMPTY, ProviderQuotaApi.parseSub2Api("""{"isValid":false,"remaining":3,"unit":"USD"}""", 1L)!!.level)
        assertNull("not a sub2api answer", ProviderQuotaApi.parseSub2Api("""{"object":"list"}""", 1L))
        assertEquals("https://relay.example/v1/usage", ProviderQuotaApi.relaySub2ApiRequest("https://relay.example", "k").url.toString())
    }

    @Test
    fun `new api billing pair`() {
        val quota = ProviderQuotaApi.parseNewApi(
            """{"object":"billing_subscription","has_payment_method":true,"soft_limit_usd":50.0,"hard_limit_usd":50.0,"system_hard_limit_usd":50.0,"access_until":0}""",
            """{"object":"list","total_usage":1250.0}""", 1L,
        )!!
        assertEquals(37.5, quota.balances.single().total, 1e-9)
        assertEquals("the site's own credit unit is not named", "", quota.balances.single().currency)
        assertEquals("no 'low' warning in an unknown unit", QuotaLevel.OK, ProviderQuota(balances = listOf(Balance("", 0.5))).level)
        assertEquals(QuotaLevel.EMPTY, ProviderQuota(balances = listOf(Balance("", 0.0))).level)
        assertTrue(ProviderQuotaApi.parseNewApi("""{"hard_limit_usd":100000000}""", """{"total_usage":5}""", 1L)!!.unlimited)
        assertNull(ProviderQuotaApi.parseNewApi("""{"error":{"message":"x"}}""", "{}", 1L))
        val (sub, usage) = ProviderQuotaApi.relayNewApiRequests("https://relay.example", "k")
        assertEquals("https://relay.example/v1/dashboard/billing/subscription", sub.url.toString())
        assertEquals("https://relay.example/v1/dashboard/billing/usage", usage.url.toString())
    }

    @Test
    fun `the agent reads what is left, never what was spent, and never a key`() {
        val ready = ProviderQuotaRepository.State.Ready(
            ProviderQuota(
                balances = listOf(Balance("CNY", 9.12, 0.0, 9.12)),
                windows = listOf(UsageWindow("5h", 9, 1_800_000_000L + 12_000)),
                plan = "plus",
            ),
        )
        val text = ProviderQuotaText.describe("deepseek", ready, now = 1_800_000_000_000L)
        assertTrue(text, text.startsWith("deepseek: plan plus; remaining 9.12 CNY (granted 0.00, topped up 9.12); 5h window: 91% left, resets in 3h 20m"))
        assertTrue(ProviderQuotaText.describe("x", ProviderQuotaRepository.State.Console("https://c.example")).contains("https://c.example"))
        assertTrue(ProviderQuotaText.describe("x", ProviderQuotaRepository.State.Unsupported).contains("no balance endpoint"))
        assertTrue(ProviderQuotaText.describe("x", ProviderQuotaRepository.State.Failed("HTTP 500", null)).contains("could not read (HTTP 500)"))
    }

    @Test
    fun `command code reads the credits call its own usage screen makes`() {
        val cc = instance(base = "https://api.commandcode.ai/provider/v1")
        assertEquals(QuotaKind.COMMAND_CODE, ProviderQuotaApi.detect(cc))
        assertNull("a look-alike host is not it", ProviderQuotaApi.detect(instance(base = "http://api.commandcode.ai.evil.example/v1")))
        val request = ProviderQuotaApi.request(QuotaKind.COMMAND_CODE, cc, "k")
        assertEquals("https://api.commandcode.ai/alpha/billing/credits", request.url.toString())
        assertEquals("Bearer k", request.header("Authorization"))
        val q = ProviderQuotaApi.parse(
            QuotaKind.COMMAND_CODE,
            """{"credits":{"monthlyCredits":12.5,"purchasedCredits":3,"freeCredits":1,"planId":"individual-pro","windowLimits":{"limited":true,
              "fiveHour":{"used":30,"cap":100,"resetAt":1790000000000},"weekly":{"used":50,"cap":200,"resetAt":1790500000000}}}}""",
            now = 1L,
        )!!
        assertEquals(16.5, q.balances.single().total, 0.0001)
        assertEquals("USD", q.balances.single().currency)
        assertEquals(listOf(30, 25), q.windows.map { it.usedPercent })
        assertEquals(1_790_000_000L, q.windows[0].resetAtEpochSec)
        assertEquals("pro", q.plan)
    }

    @Test
    fun `command code windows are shown only while limited, and a body of another shape is no answer`() {
        val q = ProviderQuotaApi.parse(
            QuotaKind.COMMAND_CODE,
            """{"credits":{"monthlyCredits":0,"windowLimits":{"limited":false,"fiveHour":{"used":1,"cap":2,"resetAt":1}}}}""", 1L,
        )!!
        assertTrue(q.windows.isEmpty())
        assertEquals(QuotaLevel.EMPTY, q.level)
        assertNull(ProviderQuotaApi.parse(QuotaKind.COMMAND_CODE, """{"error":"nope"}""", 1L))
    }

    @Test
    fun `vercel poe and novita read the balance call their docs name`() {
        val vercel = instance(base = "https://ai-gateway.vercel.sh/v1")
        assertEquals(QuotaKind.VERCEL, ProviderQuotaApi.detect(vercel))
        assertEquals("https://ai-gateway.vercel.sh/v1/credits", ProviderQuotaApi.request(QuotaKind.VERCEL, vercel, "k").url.toString())
        assertEquals(95.5, ProviderQuotaApi.parse(QuotaKind.VERCEL, """{"balance":"95.50","total_used":"4.50"}""", 1L)!!.balances.single().total, 0.0001)

        val poe = instance(base = "https://api.poe.com/v1")
        assertEquals(QuotaKind.POE, ProviderQuotaApi.detect(poe))
        assertEquals("https://api.poe.com/usage/current_balance", ProviderQuotaApi.request(QuotaKind.POE, poe, "k").url.toString())
        val points = ProviderQuotaApi.parse(QuotaKind.POE, """{"current_point_balance":1500}""", 1L)!!.balances.single()
        assertEquals(1500.0, points.total, 0.0001)
        assertEquals("points", points.currency)

        val novita = instance(base = "https://api.novita.ai/openai")
        assertEquals(QuotaKind.NOVITA, ProviderQuotaApi.detect(novita))
        assertEquals("https://api.novita.ai/openapi/v1/billing/balance/detail", ProviderQuotaApi.request(QuotaKind.NOVITA, novita, "k").url.toString())
        val usd = ProviderQuotaApi.parse(
            QuotaKind.NOVITA,
            """{"availableBalance":"1000000","cashBalance":"800000","creditLimit":"200000","pendingCharges":"0","outstandingInvoices":"0"}""", 1L,
        )!!.balances.single()
        assertEquals(100.0, usd.total, 0.0001)
    }

    @Test
    fun `these hosts match exactly, a look-alike never gets a key or a link`() {
        assertNull(ProviderQuotaApi.detect(instance(base = "https://api.poe.com.evil.example/v1")).takeIf { it != QuotaKind.RELAY })
        assertEquals(QuotaKind.CONSOLE, ProviderQuotaApi.detect(instance(base = "https://api.groq.com/openai/v1")))
        assertEquals("https://console.groq.com/settings/billing", ProviderQuotaApi.consoleUrl(instance(base = "https://api.groq.com/openai/v1")))
        assertNull(ProviderQuotaApi.consoleUrl(instance(base = "https://api.groq.com.evil.example/v1")))
        assertNull(ProviderQuotaApi.parse(QuotaKind.NOVITA, """{"error":"x"}""", 1L))
    }

    @Test
    fun `stepfun reads its accounts call`() {
        val step = instance(base = "https://api.stepfun.com/v1")
        assertEquals(QuotaKind.STEPFUN, ProviderQuotaApi.detect(step))
        assertEquals("https://api.stepfun.com/v1/accounts", ProviderQuotaApi.request(QuotaKind.STEPFUN, step, "k").url.toString())
        val b = ProviderQuotaApi.parse(
            QuotaKind.STEPFUN,
            """{"object":"account","type":"prepaid","balance":12.5,"total_cash_balance":10.0,"total_voucher_balance":26.0}""", 1L,
        )!!.balances.single()
        assertEquals(12.5, b.total, 0.0001)
        assertEquals("", b.currency)
        assertNull(ProviderQuotaApi.parse(QuotaKind.STEPFUN, """{"error":{"message":"x"}}""", 1L))
    }
}
