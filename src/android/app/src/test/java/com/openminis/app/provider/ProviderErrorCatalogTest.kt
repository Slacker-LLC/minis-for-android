package com.openminis.app.provider

import com.openminis.app.provider.ProviderErrorCatalog.Category
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each case is a body (or message) the vendor documents for that failure; see the PR for the source of each. */
class ProviderErrorCatalogTest {
    private fun cat(text: String) = ProviderErrorCatalog.categoryOf(text)

    @Test
    fun describeKeepsStatusLabelsAndMessage() {
        assertEquals(
            "[429] insufficient_quota: You exceeded your current quota",
            ProviderErrorCatalog.describe(
                429,
                """{"error":{"message":"You exceeded your current quota","type":"insufficient_quota","code":"insufficient_quota"}}""",
            ),
        )
        // Anthropic: type only, no code.
        assertEquals(
            "[402] billing_error: Your credit balance is too low",
            ProviderErrorCatalog.describe(402, """{"type":"error","error":{"type":"billing_error","message":"Your credit balance is too low"}}"""),
        )
        // Zhipu: a numeric business code inside a string.
        assertEquals(
            "[429] 1113: Insufficient balance or no resource package. Please recharge.",
            ProviderErrorCatalog.describe(429, """{"error":{"code":"1113","message":"Insufficient balance or no resource package. Please recharge."}}"""),
        )
        // DashScope native: flat code/message.
        assertEquals(
            "[400] Arrearage: Access denied, please make sure your account is in good standing.",
            ProviderErrorCatalog.describe(400, """{"code":"Arrearage","message":"Access denied, please make sure your account is in good standing.","request_id":"x"}"""),
        )
        // MiniMax: base_resp.
        assertEquals(
            "[200] 1008: insufficient balance",
            ProviderErrorCatalog.describe(200, """{"base_resp":{"status_code":1008,"status_msg":"insufficient balance"}}"""),
        )
        // Gemini streams a one-element array; the code equal to the HTTP status is not repeated.
        assertEquals(
            "[429] RESOURCE_EXHAUSTED: Quota exceeded",
            ProviderErrorCatalog.describe(429, """[{"error":{"code":429,"message":"Quota exceeded","status":"RESOURCE_EXHAUSTED"}}]"""),
        )
        // xAI: the error is a bare string.
        assertEquals("[400] Incorrect API key provided", ProviderErrorCatalog.describe(400, """{"code":"","error":"Incorrect API key provided"}"""))
    }

    @Test
    fun describeFallsBackToTheRawBodyWhenThereIsNoMessage() {
        assertEquals("HTTP 502: <html>Bad Gateway</html>", ProviderErrorCatalog.describe(502, "<html>Bad Gateway</html>"))
        assertEquals("""HTTP 500: {"error":{}}""", ProviderErrorCatalog.describe(500, """{"error":{}}"""))
    }

    @Test
    fun outOfBalanceInEachVendorsWords() {
        assertEquals(Category.BALANCE, cat("[429] insufficient_quota: You exceeded your current quota, please check your plan and billing details."))
        assertEquals(Category.BALANCE, cat("[402] billing_error: Your credit balance is too low to access the Anthropic API."))
        assertEquals(Category.BALANCE, cat("[402] Insufficient Balance")) // DeepSeek
        assertEquals(Category.BALANCE, cat("[429] 1113: Insufficient balance or no resource package. Please recharge.")) // Zhipu
        assertEquals(Category.BALANCE, cat("[400] Arrearage: Access denied, please make sure your account is in good standing.")) // DashScope
        assertEquals(Category.BALANCE, cat("[429] exceeded_current_quota_error: Your account is suspended due to insufficient balance")) // Kimi
        assertEquals(Category.BALANCE, cat("[402] Insufficient credits. Add more using https://openrouter.ai/settings/credits")) // OpenRouter
        assertEquals(Category.BALANCE, cat("[402] CreditsError: Insufficient account funds")) // OpenCode Zen
        assertEquals(Category.BALANCE, cat("[429] Account invoice is overdue")) // OpenCode Zen
        assertEquals(Category.BALANCE, cat("[200] 1008: insufficient balance")) // MiniMax
    }

    @Test
    fun aPlanOrUsageWindowIsNotABalance() {
        assertEquals(Category.PLAN_LIMIT, cat("[429] 1308: Usage limit reached for 5 hours. Your limit will reset at 2026-10-01 00:00:00"))
        assertEquals(Category.PLAN_LIMIT, cat("[429] 1310: Weekly/Monthly Limit Exhausted. Your limit will reset at 2026-10-01 00:00:00"))
        assertEquals(Category.PLAN_LIMIT, cat("[429] 1309: Your GLM Coding Plan package has expired and is temporarily unavailable."))
        assertEquals(Category.PLAN_LIMIT, cat("[429] 1311: Your current subscription plan does not yet include access to glm-5"))
        assertEquals(Category.PLAN_LIMIT, cat("[429] GoUsageLimitError: Go usage limit exceeded")) // OpenCode Zen
        assertEquals(Category.PLAN_LIMIT, cat("Gemini API error 429: RESOURCE_EXHAUSTED You exceeded your current quota, please check your plan"))
        assertEquals(Category.PLAN_LIMIT, cat("[403] FreeTierOnly: The free tier of the model has been exhausted")) // DashScope
    }

    @Test
    fun contentPolicyOverloadAndRegion() {
        assertEquals(Category.CONTENT_POLICY, cat("[400] content_filter: The request was rejected because it was considered high risk")) // Kimi
        assertEquals(Category.CONTENT_POLICY, cat("[400] DataInspectionFailed: Input data may contain inappropriate content.")) // DashScope
        assertEquals(Category.CONTENT_POLICY, cat("[400] Invalid prompt: your prompt was flagged as potentially violating our usage policy.")) // OpenAI
        assertEquals(Category.CONTENT_POLICY, cat("[403] Input flagged by moderation")) // OpenRouter
        assertEquals(Category.OVERLOADED, cat("[529] overloaded_error: Overloaded")) // Anthropic
        assertEquals(Category.OVERLOADED, cat("[503] server_is_overloaded: Our servers are currently overloaded")) // OpenAI
        assertEquals(Category.OVERLOADED, cat("[503] engine_overloaded_error: The engine is currently overloaded")) // Kimi
        assertEquals(Category.REGION, cat("[403] unsupported_country_region_territory: Country, region, or territory not supported")) // OpenAI
        assertEquals(Category.REGION, cat("Gemini API error 400: User location is not supported for the API use.")) // Gemini
    }

    @Test
    fun ordinaryFailuresAreNotAccountLevel() {
        assertNull(cat("[429] rate_limit_exceeded: Rate limit reached for requests"))
        assertNull(cat("[400] invalid_request_error: max_tokens too large"))
        assertNull(cat("[401] invalid_api_key: Incorrect API key provided"))
        assertNull(cat("[404] model_not_found: The model does not exist"))
        assertNull(cat("[400] insufficient permissions for this model"))
        assertNull(cat("Too many requests"))
    }

    @Test
    fun onlyAnAccountCapMakesA429Permanent() {
        assertTrue(ProviderErrorCatalog.isAccountCap("""{"error":{"code":"insufficient_quota","message":"You exceeded your current quota"}}"""))
        assertTrue(ProviderErrorCatalog.isAccountCap("""{"error":{"code":"1113","message":"Insufficient balance or no resource package. Please recharge."}}"""))
        assertTrue(ProviderErrorCatalog.isAccountCap("""{"error":{"code":"1308","message":"Usage limit reached for 5 hours."}}"""))
        // A throttle, an overload and a safety refusal clear (or not) on their own terms: not an account cap.
        assertFalse(ProviderErrorCatalog.isAccountCap("""{"error":{"code":"rate_limit_exceeded","message":"Rate limit reached"}}"""))
        assertFalse(ProviderErrorCatalog.isAccountCap("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}"""))
        assertFalse(ProviderErrorCatalog.isAccountCap("Rate limited"))
    }
}
