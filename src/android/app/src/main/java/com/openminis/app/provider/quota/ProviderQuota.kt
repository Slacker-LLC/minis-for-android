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

    private fun lowThreshold(currency: String) = when (currency.uppercase()) {
        "CNY", "RMB" -> 5.0
        else -> 1.0
    }
}

/** Which service's endpoint answers for an instance. */
enum class QuotaKind { DEEPSEEK, MOONSHOT, SILICONFLOW, OPENROUTER, CHATGPT }

object ProviderQuotaApi {
    /** The kind for [instance], or null when nothing is known to answer; the host is matched exactly, never by substring. */
    fun detect(instance: ProviderInstance, manualBearer: Boolean = false): QuotaKind? {
        if (instance.providerType == ProviderType.openAI && instance.credentialType == ProviderCredential.oauth) {
            // A manual bearer is not a ChatGPT session token.
            return if (manualBearer) null else QuotaKind.CHATGPT
        }
        if (instance.providerType == ProviderType.openRouter && instance.customBaseURL == null) return QuotaKind.OPENROUTER
        val host = instance.customBaseURL?.toHttpUrlOrNull()?.host ?: return null
        fun host(vararg names: String) = names.any { host == it }
        return when {
            host("api.deepseek.com") -> QuotaKind.DEEPSEEK
            host("api.moonshot.cn", "api.moonshot.ai") -> QuotaKind.MOONSHOT
            host("api.siliconflow.cn", "api.siliconflow.com") -> QuotaKind.SILICONFLOW
            host("openrouter.ai") -> QuotaKind.OPENROUTER
            else -> null
        }
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

    /** "5h", "7d", "30m": the length of a usage window. */
    fun windowLabel(seconds: Long): String = when {
        seconds <= 0 -> "-"
        seconds % 86_400 == 0L -> "${seconds / 86_400}d"
        seconds % 3_600 == 0L -> "${seconds / 3_600}h"
        else -> "${seconds / 60}m"
    }
}
