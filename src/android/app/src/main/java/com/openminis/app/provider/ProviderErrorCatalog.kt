package com.openminis.app.provider

import org.json.JSONArray
import org.json.JSONObject

/**
 * What the providers' error bodies say, read in one place. Every vendor words the same few account-level
 * failures differently (and several send them under a misleading HTTP status: Zhipu code 1113 "overdue" arrives
 * as 429, DashScope `Arrearage` as 400), so the status alone cannot name them. The phrases below come from each
 * vendor's published error reference; the provider mappers use them to keep the body of a 4xx that is not a
 * plain throttle or bad key, and the chat's error banner uses them to word it.
 *
 * Phrases are matched against the lower-cased detail text with quotes stripped, so a code (`insufficient_quota`)
 * and its message ("You exceeded your current quota") both count. Pure, so each vendor's wording is unit-tested.
 */
internal object ProviderErrorCatalog {

    enum class Category {
        /** Out of money or credit: top up, or use another provider. */
        BALANCE,

        /** A plan, free-tier or usage window is used up, expired or does not include the model: wait or upgrade. */
        PLAN_LIMIT,

        /** The provider's safety/moderation layer refused the content. */
        CONTENT_POLICY,

        /** The provider is up but has no capacity right now: a retry later is the answer. */
        OVERLOADED,

        /** The account's country/region is not served. */
        REGION,
    }

    private val REGION = listOf(
        "unsupported_country_region_territory", // OpenAI 403
        "country, region, or territory",
        "user location is not supported", // Gemini
        "location is not supported",
        "not available in your country",
        "not available in your region",
    )

    private val BALANCE = listOf(
        "insufficient_quota", // OpenAI, Kimi's sibling code, MiniMax-compatible gateways
        "exceeded_current_quota_error", // Moonshot / Kimi
        "billing_error", // Anthropic 402, OpenCode Zen
        "billing_hard_limit_reached",
        "credit balance is too low", // Anthropic message
        "insufficient balance", // DeepSeek 402, Zhipu 1113, MiniMax 1008
        "insufficient_balance",
        "insufficient credits", // OpenRouter 402
        "insufficient account funds", // OpenCode Zen
        "insufficient funds",
        "account invoice is overdue", // OpenCode Zen
        "arrearage", // DashScope: account in arrears
        "overdue",
        "payment_required",
        "payment required",
        "creditlimitexceeded", // OpenCode Zen
        "credit limit exceeded",
        "no resource package", // Zhipu 1113
        "please recharge",
        "out of credits",
        "out of budget",
    )

    private val PLAN_LIMIT = listOf(
        "usage limit", // OpenAI org/project, Zhipu 1308, OpenCode Zen
        "usage_limit",
        "limit exhausted", // Zhipu 1310
        "limit will reset", // Zhipu 1308/1310
        "package has expired", // Zhipu 1309
        "plan does not yet include", // Zhipu 1311
        "usage_not_included", // OpenAI
        "gousagelimiterror", // OpenCode Zen
        "freeusagelimiterror",
        "freetieronly", // DashScope
        "free tier",
        "allocationquota",
        "budget exceeded",
        "monthly limit",
        "weekly limit",
        "daily limit",
        "quota exceeded",
        "quota_exceeded",
        "exceeded your current quota",
    )

    private val CONTENT_POLICY = listOf(
        "content_filter", // Kimi, Azure
        "content filter",
        "content_policy",
        "content policy",
        "content_moderation",
        "moderation", // OpenRouter
        "responsibleaipolicyviolation", // Azure
        "datainspectionfailed", // DashScope
        "usage policy", // OpenAI / Anthropic
        "safety system", // OpenAI images
        "unsafe or sensitive",
        "sensitive content", // Zhipu 1301
        "inappropriate content",
        "considered high risk", // Kimi
    )

    private val OVERLOADED = listOf(
        "overloaded", // Anthropic 529, OpenAI server_is_overloaded, OpenRouter, Kimi engine_overloaded_error
        "at capacity",
        "slow_down",
    )

    private val BALANCE_NOUNS = listOf("balance", "credit", "fund", "quota")

    private fun normalize(text: String) = text.lowercase().replace("`", "").replace("'", "").replace("\"", "")

    /** "You have insufficient balance", "Insufficient credits": the account, not the request. */
    private fun looksOutOfBalance(d: String) = "insufficient" in d && BALANCE_NOUNS.any { it in d }

    /** The account-level meaning of [text] (a body or a mapped detail), or null when it is an ordinary failure. */
    fun categoryOf(text: String): Category? {
        val d = normalize(text)
        return when {
            REGION.any { it in d } -> Category.REGION
            BALANCE.any { it in d } || looksOutOfBalance(d) -> Category.BALANCE
            PLAN_LIMIT.any { it in d } -> Category.PLAN_LIMIT
            CONTENT_POLICY.any { it in d } -> Category.CONTENT_POLICY
            OVERLOADED.any { it in d } -> Category.OVERLOADED
            else -> null
        }
    }

    /**
     * True when a 429 is an account cap (balance, plan window) rather than a throttle that clears by itself. The
     * mappers turn such a 429 into a provider error that keeps the body; a throttle stays a bare rate limit.
     */
    fun isAccountCap(body: String): Boolean = when (categoryOf(body)) {
        Category.BALANCE, Category.PLAN_LIMIT, Category.REGION -> true
        else -> false
    }

    /**
     * "[429] insufficient_quota: You exceeded your current quota" from a vendor error body: the status first (the
     * chat reads it back), then the vendor's own error labels, then its message. A body that is not JSON, or has
     * no message, falls back to the first 500 characters so nothing the vendor said is lost.
     */
    fun describe(status: Int, body: String): String {
        val root = parseRoot(body) ?: return "HTTP $status: ${body.take(500)}"
        val error = root.optJSONObject("error")
        val base = root.optJSONObject("base_resp") // MiniMax
        val message = firstText(
            error?.optString("message"),
            root.optString("message"),
            root.optString("msg"),
            (root.opt("error") as? String),
            base?.optString("status_msg"),
        ) ?: return "HTTP $status: ${body.take(500)}"
        val labels = listOfNotNull(
            error?.opt("type"), error?.opt("code"), error?.opt("status"),
            root.opt("code"), base?.opt("status_code"),
        ).map { it.toString() }
            .filter { it.isNotBlank() && it != "null" && it != "0" && it != status.toString() }
            .distinct()
        val prefix = if (labels.isEmpty()) "" else labels.joinToString("/") + ": "
        return "[$status] $prefix$message"
    }

    private fun parseRoot(body: String): JSONObject? = try {
        val trimmed = body.trim()
        // Gemini streams a one-element array around the error object.
        if (trimmed.startsWith("[")) JSONArray(trimmed).optJSONObject(0) else JSONObject(trimmed)
    } catch (_: Exception) {
        null
    }

    private fun firstText(vararg candidates: String?): String? =
        candidates.firstOrNull { !it.isNullOrBlank() && it != "null" }
}
