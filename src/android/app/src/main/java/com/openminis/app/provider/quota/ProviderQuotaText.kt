package com.openminis.app.provider.quota

import java.util.Locale

/** The quota as plain English for the agent: what is left, never what was spent, and never a key. */
object ProviderQuotaText {
    fun describe(label: String, state: ProviderQuotaRepository.State?, now: Long = System.currentTimeMillis()): String = when (state) {
        null, ProviderQuotaRepository.State.Loading -> "$label: not read yet"
        ProviderQuotaRepository.State.Unsupported -> "$label: this service offers no balance endpoint"
        is ProviderQuotaRepository.State.Console -> "$label: no balance API; check ${state.url}"
        is ProviderQuotaRepository.State.Failed -> "$label: could not read (${state.message})" +
            (state.last?.let { "; last known: " + quotaSummary(it, now) } ?: "")
        is ProviderQuotaRepository.State.Ready -> "$label: " + quotaSummary(state.quota, now)
    }

    internal fun quotaSummary(quota: ProviderQuota, now: Long): String {
        val parts = ArrayList<String>()
        quota.plan?.let { parts.add("plan $it") }
        if (quota.unlimited) parts.add("no limit")
        for (b in quota.balances) {
            val amount = String.format(Locale.US, "%.2f", b.total)
            val detail = listOfNotNull(
                b.granted?.let { "granted " + String.format(Locale.US, "%.2f", it) },
                b.toppedUp?.let { "topped up " + String.format(Locale.US, "%.2f", it) },
            ).joinToString(", ")
            parts.add(("remaining $amount ${b.currency}").trim() + if (detail.isEmpty()) "" else " ($detail)")
        }
        for (w in quota.windows) {
            val left = (100 - w.usedPercent).coerceIn(0, 100)
            val reset = w.resetAtEpochSec?.let { it * 1000L - now }?.takeIf { it > 0 }?.let { ", resets in " + duration(it) }.orEmpty()
            parts.add("${w.label} window: $left% left$reset")
        }
        if (quota.level == QuotaLevel.EMPTY) parts.add("EMPTY: calls will fail")
        else if (quota.level == QuotaLevel.LOW) parts.add("LOW")
        return parts.joinToString("; ").ifEmpty { "nothing to report" }
    }

    internal fun duration(ms: Long): String {
        val minutes = (ms / 60_000L).coerceAtLeast(0L)
        val days = minutes / 1_440L
        val hours = (minutes % 1_440L) / 60L
        val mins = minutes % 60L
        return when {
            days > 0 -> "${days}d ${hours}h"
            hours > 0 -> "${hours}h ${mins}m"
            else -> "${mins}m"
        }
    }
}
