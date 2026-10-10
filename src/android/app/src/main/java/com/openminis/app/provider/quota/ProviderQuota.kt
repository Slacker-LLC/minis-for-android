package com.openminis.app.provider.quota

import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONObject

/**
 * What a provider says is left on the account: a money balance (DeepSeek, Moonshot, SiliconFlow, OpenRouter) or usage
 * windows of a subscription (ChatGPT sign-in). The sources are each service's own documented endpoint; a service that
 * has no such endpoint, or a custom relay, is simply not supported and shows nothing.
 */
data class Balance(
    val currency: String,
    val total: Double,
    val granted: Double? = null,
    val toppedUp: Double? = null,
)

/** A subscription window, e.g. the 5-hour or the weekly allowance. */
data class UsageWindow(val label: String, val usedPercent: Int, val resetAtEpochSec: Long? = null)

enum class QuotaLevel { OK, LOW, EMPTY }

data class ProviderQuota(
    val balances: List<Balance> = emptyList(),
    val windows: List<UsageWindow> = emptyList(),
    /** The service's own verdict that calls will go through, when it gives one. */
    val available: Boolean? = null,
    val plan: String? = null,
    /** The service says there is no limit to show (a New API token with unlimited quota). */
    val unlimited: Boolean = false,
    val fetchedAtMs: Long = 0L,
) {
    /**
     * EMPTY: the service says no, a window is spent, or every balance is gone. LOW: a balance under a few currency
     * units or a window nearly spent. A balance in another currency that still has money keeps the account out of EMPTY.
     */
    val level: QuotaLevel
        get() {
            if (available == false) return QuotaLevel.EMPTY
            if (windows.any { it.usedPercent >= 100 }) return QuotaLevel.EMPTY
            if (balances.isNotEmpty() && balances.all { it.total <= 0.0 }) return QuotaLevel.EMPTY
            val lowBalance = balances.any { it.total > 0.0 && it.total < lowThreshold(it.currency) }
            val lowWindow = windows.any { it.usedPercent >= 90 }
            return if (lowBalance || lowWindow) QuotaLevel.LOW else QuotaLevel.OK
        }

    // A balance in a unit the service does not name (a relay shows its own credits) is only ever empty, never "low".
    private fun lowThreshold(currency: String) = when (currency.uppercase()) {
        "" -> 0.0
        "CNY", "RMB" -> 5.0
        else -> 1.0
    }
}

/** Which service's endpoint answers for an instance. */
enum class QuotaKind {
    DEEPSEEK, MOONSHOT, SILICONFLOW, OPENROUTER, CHATGPT,
    /** Claude subscription (OAuth): `GET /api/oauth/usage`. */
    CLAUDE,
    /** Kimi Code subscription (OAuth): `GET /coding/v1/usages`. */
    KIMI_CODE,
    /** Command Code (API key): `GET https://api.commandcode.ai/alpha/billing/credits`, the call its CLI's `/usage` makes. */
    COMMAND_CODE,
    /** A relay on a custom https base (Sub2API / New API style); asks the same origin the chat requests already go to. */
    RELAY,
    /** No documented balance API: the page only links to the service's own console. */
    CONSOLE,
}

object ProviderQuotaApi {
    /** The kind for [instance], or null when nothing is known to answer; the host is matched exactly, never by substring. */
    fun detect(instance: ProviderInstance, manualBearer: Boolean = false): QuotaKind? {
        if (instance.credentialType == ProviderCredential.oauth) {
            // A manual bearer is not the service's own session token.
            if (manualBearer) return null
            return when (instance.providerType) {
                ProviderType.openAI, ProviderType.openAIResponses -> QuotaKind.CHATGPT
                ProviderType.anthropic -> QuotaKind.CLAUDE
                ProviderType.kimiCode -> QuotaKind.KIMI_CODE
                else -> null
            }
        }
        if (instance.providerType == ProviderType.openRouter && instance.customBaseURL == null) return QuotaKind.OPENROUTER
        val url = instance.customBaseURL?.toHttpUrlOrNull() ?: return null
        val host = url.host
        fun host(vararg names: String) = names.any { host == it }
        return when {
            host("api.deepseek.com") -> QuotaKind.DEEPSEEK
            host("api.moonshot.cn", "api.moonshot.ai") -> QuotaKind.MOONSHOT
            host("api.siliconflow.cn", "api.siliconflow.com") -> QuotaKind.SILICONFLOW
            host("openrouter.ai") -> QuotaKind.OPENROUTER
            host("api.commandcode.ai") -> QuotaKind.COMMAND_CODE
            host in CONSOLES -> QuotaKind.CONSOLE
            // The official endpoints of the big three have nothing to ask with an API key.
            host("api.openai.com", "api.anthropic.com", "generativelanguage.googleapis.com", "api.x.ai") -> null
            url.isHttps && instance.providerType in RELAY_TYPES && instance.credentialType == ProviderCredential.apiKey -> QuotaKind.RELAY
            else -> null
        }
    }

    private val XIAOMI_HOSTS = setOf(
        "api.xiaomimimo.com", "token-plan-cn.xiaomimimo.com", "token-plan-sgp.xiaomimimo.com", "token-plan-ams.xiaomimimo.com",
    )

    /** Services whose docs name no balance API: the host and the page where the user reads usage and credits. */
    private val CONSOLES: Map<String, String> =
        XIAOMI_HOSTS.associateWith { "https://platform.xiaomimimo.com" }
    private val RELAY_TYPES = setOf(ProviderType.openAI, ProviderType.openAIResponses, ProviderType.anthropic)

    /** The console page to open for a [QuotaKind.CONSOLE] provider (no balance API is documented for it). */
    fun consoleUrl(instance: ProviderInstance): String? {
        val host = instance.customBaseURL?.toHttpUrlOrNull()?.host ?: return null
        return CONSOLES[host]
    }

    /** The request for [kind]. The key goes only to the service's own host, whatever path the instance's base had. */
    fun request(kind: QuotaKind, instance: ProviderInstance, key: String, accountId: String? = null): Request {
        val host = instance.customBaseURL?.toHttpUrlOrNull()?.host
        val builder = Request.Builder().get().header("Accept", "application/json")
        return when (kind) {
            QuotaKind.DEEPSEEK -> builder.url("https://api.deepseek.com/user/balance")
            QuotaKind.MOONSHOT -> builder.url("https://${host ?: "api.moonshot.cn"}/v1/users/me/balance")
            QuotaKind.SILICONFLOW -> builder.url("https://${host ?: "api.siliconflow.cn"}/v1/user/info")
            QuotaKind.OPENROUTER -> builder.url("https://openrouter.ai/api/v1/credits")
            QuotaKind.CHATGPT -> builder.url("https://chatgpt.com/backend-api/wham/usage")
                .header("User-Agent", "codex-cli")
                .also { b -> accountId?.takeIf { it.isNotBlank() }?.let { b.header("ChatGPT-Account-Id", it) } }
            QuotaKind.CLAUDE -> builder.url("https://api.anthropic.com/api/oauth/usage")
                .header("anthropic-beta", "oauth-2025-04-20")
            QuotaKind.KIMI_CODE -> builder.url("https://api.kimi.com/coding/v1/usages")
            QuotaKind.COMMAND_CODE -> builder.url("https://api.commandcode.ai/alpha/billing/credits")
            QuotaKind.RELAY, QuotaKind.CONSOLE -> throw IllegalArgumentException("$kind has no single request")
        }.header("Authorization", "Bearer $key").build()
    }

    /** The request OpenRouter's key endpoint (works with an ordinary key; /credits needs a management key). */
    fun openRouterKeyRequest(key: String): Request =
        Request.Builder().get().url("https://openrouter.ai/api/v1/key")
            .header("Accept", "application/json").header("Authorization", "Bearer $key").build()

    /** Null when the body is not what the service documents. */
    fun parse(kind: QuotaKind, body: String, now: Long): ProviderQuota? = runCatching {
        val json = JSONObject(body)
        when (kind) {
            QuotaKind.DEEPSEEK -> {
                val infos = json.getJSONArray("balance_infos")
                ProviderQuota(
                    balances = (0 until infos.length()).map { i ->
                        val o = infos.getJSONObject(i)
                        Balance(
                            currency = o.getString("currency"),
                            total = o.getString("total_balance").toDouble(),
                            granted = o.optString("granted_balance").toDoubleOrNull(),
                            toppedUp = o.optString("topped_up_balance").toDoubleOrNull(),
                        )
                    },
                    available = if (json.has("is_available")) json.getBoolean("is_available") else null,
                    fetchedAtMs = now,
                )
            }
            QuotaKind.MOONSHOT -> {
                val data = json.getJSONObject("data")
                ProviderQuota(
                    balances = listOf(
                        Balance(
                            currency = "CNY",
                            total = data.getDouble("available_balance"),
                            granted = data.optDouble("voucher_balance").takeIf { !it.isNaN() },
                            toppedUp = data.optDouble("cash_balance").takeIf { !it.isNaN() },
                        ),
                    ),
                    fetchedAtMs = now,
                )
            }
            QuotaKind.SILICONFLOW -> {
                val data = json.getJSONObject("data")
                ProviderQuota(
                    balances = listOf(
                        Balance(
                            currency = "CNY",
                            total = data.getString("totalBalance").toDouble(),
                            granted = data.optString("balance").toDoubleOrNull(),
                            toppedUp = data.optString("chargeBalance").toDoubleOrNull(),
                        ),
                    ),
                    fetchedAtMs = now,
                )
            }
            QuotaKind.OPENROUTER -> {
                val data = json.getJSONObject("data")
                if (data.has("total_credits")) {
                    ProviderQuota(
                        balances = listOf(Balance("USD", data.getDouble("total_credits") - data.getDouble("total_usage"))),
                        fetchedAtMs = now,
                    )
                } else {
                    // /key: the key's own spending limit; no limit means there is no number to show.
                    val remaining = data.optDouble("limit_remaining").takeIf { !it.isNaN() && !data.isNull("limit_remaining") }
                        ?: return@runCatching null
                    ProviderQuota(balances = listOf(Balance("USD", remaining)), fetchedAtMs = now)
                }
            }
            QuotaKind.CLAUDE -> {
                val windows = listOf("five_hour" to "5h", "seven_day" to "7d", "seven_day_sonnet" to "7d Sonnet", "seven_day_overage_included" to "7d").mapNotNull { (key, label) ->
                    json.optJSONObject(key)?.takeIf { it.has("utilization") && !it.isNull("utilization") }?.let { w ->
                        UsageWindow(
                            label = label,
                            usedPercent = Math.round(w.getDouble("utilization")).toInt().coerceIn(0, 100),
                            resetAtEpochSec = parseIsoSeconds(w.optString("resets_at")),
                        )
                    }
                }
                if (windows.isEmpty()) return@runCatching null
                // The same label twice (7d and the overage window) would be two identical rows.
                ProviderQuota(windows = windows.distinctBy { it.label }, fetchedAtMs = now)
            }
            QuotaKind.COMMAND_CODE -> {
                // `command-code` 1.79.2 `dist/cli.mjs`, projectUsageView: credits.{monthly,purchased,free}Credits are what is LEFT, in
                // dollars; windowLimits.{fiveHour,weekly} = {used, cap, resetAt in ms}, shown only while `limited`.
                val c = json.getJSONObject("credits")
                fun left(name: String) = c.optDouble(name, 0.0).takeIf { it.isFinite() && it > 0.0 } ?: 0.0
                val limits = c.optJSONObject("windowLimits")
                val windows = if (limits?.optBoolean("limited") == true) {
                    listOf("fiveHour" to 18_000L, "weekly" to 604_800L).mapNotNull { (key, seconds) ->
                        val w = limits.optJSONObject(key) ?: return@mapNotNull null
                        val cap = w.optDouble("cap", 0.0)
                        if (!(cap > 0.0)) return@mapNotNull null
                        val resetMs = w.optLong("resetAt", 0L)
                        UsageWindow(windowLabel(seconds), (w.optDouble("used", 0.0) * 100 / cap).toInt().coerceIn(0, 100), resetMs.takeIf { it > 0 }?.div(1000))
                    }
                } else emptyList()
                ProviderQuota(
                    balances = listOf(Balance("USD", left("monthlyCredits") + left("purchasedCredits") + left("freeCredits"), left("freeCredits"), left("purchasedCredits"))),
                    windows = windows,
                    plan = c.optString("planId").removePrefix("individual-").removePrefix("teams-").takeIf { it.isNotBlank() },
                    fetchedAtMs = now,
                )
            }
            QuotaKind.KIMI_CODE -> {
                // `kimi_cli/ui/shell/usage.py`: usage (weekly) and limits[] (each with detail / window / name).
                fun row(data: JSONObject, label: String): UsageWindow? {
                    val limit = data.optString("limit").toLongOrNull() ?: return null
                    if (limit <= 0L) return null
                    val used = data.optString("used").toLongOrNull()
                        ?: data.optString("remaining").toLongOrNull()?.let { limit - it }
                        ?: return null
                    val reset = listOf("reset_at", "resetAt", "reset_time", "resetTime").firstNotNullOfOrNull { k ->
                        parseIsoSeconds(data.optString(k))
                    } ?: listOf("reset_in", "resetIn", "ttl", "window").firstNotNullOfOrNull { k ->
                        data.optString(k).toLongOrNull()?.takeIf { it > 0 }?.let { now / 1000 + it }
                    }
                    return UsageWindow(label, (used * 100 / limit).toInt().coerceIn(0, 100), reset)
                }
                val windows = ArrayList<UsageWindow>()
                json.optJSONObject("usage")?.let { row(it, it.optString("name").ifBlank { it.optString("title") }.ifBlank { "weekly" })?.let(windows::add) }
                json.optJSONArray("limits")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val detail = item.optJSONObject("detail") ?: item
                        val window = item.optJSONObject("window")
                        val label = detail.optString("name").ifBlank { detail.optString("title") }.ifBlank { item.optString("name") }
                            .ifBlank { window?.let { kimiWindowLabel(it) } ?: "limit ${i + 1}" }
                        row(detail, label)?.let(windows::add)
                    }
                }
                if (windows.isEmpty()) return@runCatching null
                ProviderQuota(windows = windows, fetchedAtMs = now)
            }
            QuotaKind.RELAY, QuotaKind.CONSOLE -> null
            QuotaKind.CHATGPT -> {
                val limit = json.optJSONObject("rate_limit")
                val windows = listOf("primary_window", "secondary_window").mapNotNull { name ->
                    limit?.optJSONObject(name)?.let { w ->
                        UsageWindow(
                            label = windowLabel(w.optLong("limit_window_seconds")),
                            usedPercent = w.getInt("used_percent"),
                            resetAtEpochSec = w.optLong("reset_at").takeIf { it > 0 },
                        )
                    }
                }
                ProviderQuota(
                    windows = windows,
                    available = limit?.takeIf { it.has("allowed") }?.let { it.getBoolean("allowed") && !it.optBoolean("limit_reached") },
                    plan = json.optString("plan_type").takeIf { it.isNotBlank() },
                    fetchedAtMs = now,
                )
            }
        }
    }.getOrNull()

    /** The relay's own origin (scheme, host, port): where its chat requests already go, so the key goes nowhere new. */
    fun relayOrigin(instance: ProviderInstance): String? =
        instance.customBaseURL?.toHttpUrlOrNull()?.takeIf { it.isHttps }?.let { it.scheme + "://" + it.host + (if (it.port != 443) ":" + it.port else "") }

    /** Sub2API's key self-introspection. */
    fun relaySub2ApiRequest(origin: String, key: String): Request =
        Request.Builder().get().url("$origin/v1/usage").header("Accept", "application/json").header("Authorization", "Bearer $key").build()

    /** The OpenAI-style billing pair New API / One API serve for a token: subscription (the limit) and usage (spent, in cents). */
    fun relayNewApiRequests(origin: String, key: String): Pair<Request, Request> {
        fun req(path: String) = Request.Builder().get().url("$origin$path").header("Accept", "application/json")
            .header("Authorization", "Bearer $key").build()
        return req("/v1/dashboard/billing/subscription") to req("/v1/dashboard/billing/usage")
    }

    /** Sub2API `GET /v1/usage` (`gateway_handler.go`: `usageQuotaLimited` / `usageUnrestricted`). */
    fun parseSub2Api(body: String, now: Long): ProviderQuota? = runCatching {
        val json = JSONObject(body)
        if (!json.has("isValid")) return@runCatching null
        val unit = json.optString("unit").ifBlank { "USD" }
        val remaining = json.optDouble("remaining").takeIf { !it.isNaN() }
        val windows = json.optJSONArray("rate_limits")?.let { arr ->
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val limit = o.optDouble("limit")
                if (limit.isNaN() || limit <= 0.0) return@mapNotNull null
                UsageWindow(
                    label = o.optString("window", "?"),
                    usedPercent = (o.optDouble("used", 0.0) * 100.0 / limit).toInt().coerceIn(0, 100),
                    resetAtEpochSec = parseIsoSeconds(o.optString("reset_at")),
                )
            }
        }.orEmpty()
        if (remaining == null && windows.isEmpty()) return@runCatching null
        ProviderQuota(
            balances = remaining?.let { listOf(Balance(unit, it)) }.orEmpty(),
            windows = windows,
            available = json.getBoolean("isValid").takeIf { !it } ?: true,
            plan = json.optString("planName").takeIf { it.isNotBlank() },
            fetchedAtMs = now,
        )
    }.getOrNull()

    /** New API / One API: remaining = limit - spent. A token with unlimited quota reports 100000000. */
    fun parseNewApi(subscriptionBody: String, usageBody: String, now: Long): ProviderQuota? = runCatching {
        val sub = JSONObject(subscriptionBody)
        if (sub.has("error")) return@runCatching null
        val limit = sub.getDouble("hard_limit_usd")
        if (limit >= 100_000_000.0) return@runCatching ProviderQuota(unlimited = true, fetchedAtMs = now)
        val spent = JSONObject(usageBody).getDouble("total_usage") / 100.0
        // The site chooses what its credits are shown in (USD, CNY or tokens), so the unit is not claimed.
        ProviderQuota(balances = listOf(Balance("", limit - spent)), fetchedAtMs = now)
    }.getOrNull()

    private fun parseIsoSeconds(text: String?): Long? =
        text?.takeIf { it.isNotBlank() }?.let { runCatching { java.time.OffsetDateTime.parse(it).toEpochSecond() }.getOrNull() }

    /** `{duration: 300, timeUnit: "TIME_UNIT_MINUTE"}` -> "5h". */
    private fun kimiWindowLabel(window: JSONObject): String? {
        val duration = window.optString("duration").toLongOrNull() ?: return null
        val unit = window.optString("timeUnit").uppercase()
        val seconds = when {
            "SECOND" in unit -> duration
            "MINUTE" in unit -> duration * 60
            "HOUR" in unit -> duration * 3_600
            "DAY" in unit -> duration * 86_400
            else -> return null
        }
        return windowLabel(seconds)
    }

    /** "5h", "7d", "30m": the length of a usage window. */
    fun windowLabel(seconds: Long): String = when {
        seconds <= 0 -> "-"
        seconds % 86_400 == 0L -> "${seconds / 86_400}d"
        seconds % 3_600 == 0L -> "${seconds / 3_600}h"
        else -> "${seconds / 60}m"
    }
}
