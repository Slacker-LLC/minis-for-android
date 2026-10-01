package com.openminis.app.ui.chat

import org.json.JSONObject
import java.util.Locale

/**
 * One assistant turn's token counts, read back from the `token_usage` JSON column that
 * ChatViewModel.persistAssistantTurn writes. Parsing is total: a malformed or partial row yields
 * null instead of throwing, because a bad usage string must never get in the way of a message.
 *
 * Ported from OpenMinis 1.14 (`ChatTokenUsage`, [T-android-usage-capsule-time]); the same five
 * keys are written here.
 */
internal data class ChatTokenUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheCreationTokens: Int,
    val cacheReadTokens: Int,
    val latestContextTokens: Int,
) {
    companion object {
        fun parse(json: String?): ChatTokenUsage? {
            if (json.isNullOrBlank()) return null
            return runCatching {
                val o = JSONObject(json)
                ChatTokenUsage(
                    inputTokens = o.optInt("inputTokens", 0),
                    outputTokens = o.optInt("outputTokens", 0),
                    cacheCreationTokens = o.optInt("cacheCreationTokens", 0),
                    cacheReadTokens = o.optInt("cacheReadTokens", 0),
                    latestContextTokens = o.optInt("latestContextTokens", 0),
                ).takeUnless { it.isEmpty }
            }.getOrNull()
        }
    }

    /** An all-zero row carries nothing worth showing. */
    private val isEmpty: Boolean
        get() = inputTokens == 0 && outputTokens == 0 && cacheReadTokens == 0 &&
            cacheCreationTokens == 0 && latestContextTokens == 0
}

/** A reply's usage and when its last turn finished. */
internal data class ReplyUsage(val usage: ChatTokenUsage, val completedAtMs: Long?)

/** 999 → "999", 1000 → "1k", 57_400 → "57.4k". */
internal fun formatUsageTokens(count: Int): String {
    if (count < 1000) return count.toString()
    val k = count / 1000.0
    return if (k % 1.0 == 0.0) "${k.toInt()}k" else String.format(Locale.US, "%.1fk", k)
}

/**
 * Share of the prompt that was served from the cache, in percent; null when nothing was read from it.
 * `inputTokens` is the uncached part of the prompt (the session usage sheet adds the three together
 * the same way), so the whole prompt is input + cache read + cache creation.
 */
internal fun cacheHitPercent(usage: ChatTokenUsage): Int? {
    if (usage.cacheReadTokens <= 0) return null
    val prompt = usage.inputTokens.toLong() + usage.cacheReadTokens + usage.cacheCreationTokens
    if (prompt <= 0L) return null
    return (usage.cacheReadTokens * 100L / prompt).toInt().coerceIn(0, 100)
}

/** "ctx:57k in:2 out:408 cache:57k (96%) +cache:3k" — ctx and the cache figures only when non-zero. */
internal fun usageSummary(usage: ChatTokenUsage): String = buildString {
    if (usage.latestContextTokens > 0) append("ctx:${formatUsageTokens(usage.latestContextTokens)} ")
    append("in:${formatUsageTokens(usage.inputTokens)}")
    append(" out:${formatUsageTokens(usage.outputTokens)}")
    if (usage.cacheReadTokens > 0) {
        append(" cache:${formatUsageTokens(usage.cacheReadTokens)}")
        cacheHitPercent(usage)?.let { append(" ($it%)") }
    }
    if (usage.cacheCreationTokens > 0) append(" +cache:${formatUsageTokens(usage.cacheCreationTokens)}")
}

/**
 * A reply that was built from several persisted turns (tool rounds) reports its LAST turn that has
 * usage: [rows] are the (token_usage JSON, created_at) pairs of its source rows in order, and a final
 * turn whose provider sent no usage must not hide the one before it.
 */
internal fun lastReplyUsage(rows: List<Pair<String?, Long>>): ReplyUsage? {
    for ((json, createdAt) in rows.asReversed()) {
        ChatTokenUsage.parse(json)?.let { return ReplyUsage(it, createdAt) }
    }
    return null
}
