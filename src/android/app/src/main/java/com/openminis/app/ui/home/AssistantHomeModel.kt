package com.openminis.app.ui.home

/**
 * [T-android-assistant-home] Pure half of the assistant home page: which quick
 * actions exist, how a persisted selection is decoded, which greeting the
 * current hour earns, and how memory numbers are rendered. No Android imports —
 * every rule here is unit-tested.
 */

/**
 * The four quick actions the 2×2 grid offers. [id] is the persisted spelling;
 * unknown ids are dropped on read so a future build's card cannot crash an
 * older one.
 */
internal enum class AssistantQuickAction(val id: String) {
    ANALYZE_SCREEN("screen"),
    OPEN_WECHAT("wechat"),
    BROWSE_WEB("web"),
    MEMORY_PRESSURE("memory"),
    ;

    companion object {
        /** Roadmap order: 分析当前屏幕 / 打开微信 / 浏览网页 / 查看内存压力. */
        val DEFAULT_ORDER: List<AssistantQuickAction> = listOf(
            ANALYZE_SCREEN,
            OPEN_WECHAT,
            BROWSE_WEB,
            MEMORY_PRESSURE,
        )

        /** Maximum cards the 2×2 grid can show. */
        const val MAX_CARDS = 4

        fun fromId(raw: String?): AssistantQuickAction? =
            entries.firstOrNull { it.id == raw }

        /**
         * Decode a persisted selection. Order is preserved, duplicates and
         * unknown ids are dropped, and an empty / fully unreadable value falls
         * back to [DEFAULT_ORDER] — the home page must never render zero cards
         * just because a preference was corrupted.
         */
        fun parseSelection(raw: String?): List<AssistantQuickAction> {
            if (raw.isNullOrBlank()) return DEFAULT_ORDER
            val parsed = raw.split(',')
                .mapNotNull { fromId(it.trim()) }
                .distinct()
                .take(MAX_CARDS)
            return parsed.ifEmpty { DEFAULT_ORDER }
        }

        fun serialize(actions: List<AssistantQuickAction>): String =
            actions.distinct().take(MAX_CARDS).joinToString(",") { it.id }

        /**
         * Flip one card. Removing the LAST remaining card is refused (a home
         * page with no quick action is a dead end) — the caller gets the
         * selection back unchanged.
         */
        fun toggle(
            current: List<AssistantQuickAction>,
            action: AssistantQuickAction,
        ): List<AssistantQuickAction> = when {
            action in current && current.size <= 1 -> current
            action in current -> current.filterNot { it == action }
            current.size >= MAX_CARDS -> current
            else -> current + action
        }
    }
}

/** Coarse time-of-day bucket behind the greeting line. */
internal enum class GreetingPeriod { MORNING, AFTERNOON, EVENING, NIGHT }

/**
 * [hourOfDay] is a 0..23 clock hour; anything outside the range is clamped so a
 * caller passing `System.currentTimeMillis()` cannot produce a nonsense bucket.
 */
internal fun greetingPeriodFor(hourOfDay: Int): GreetingPeriod = when (hourOfDay.coerceIn(0, 23)) {
    in 5..11 -> GreetingPeriod.MORNING
    in 12..17 -> GreetingPeriod.AFTERNOON
    in 18..22 -> GreetingPeriod.EVENING
    else -> GreetingPeriod.NIGHT
}

/** Device + app memory reading shown by the "memory pressure" card. */
internal data class MemoryPressureSnapshot(
    val totalBytes: Long,
    val availBytes: Long,
    val thresholdBytes: Long,
    val lowMemory: Boolean,
    val appPssBytes: Long,
) {
    val usedBytes: Long get() = (totalBytes - availBytes).coerceAtLeast(0L)

    val usedPercent: Int
        get() = if (totalBytes <= 0L) 0 else ((usedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)

    /** True when the system itself flagged pressure (or is close to it). */
    val underPressure: Boolean
        get() = lowMemory || (totalBytes > 0L && availBytes <= thresholdBytes)
}

/** Human-readable byte size used by the memory dialog (`1.2 GB`, `512 MB`). */
internal fun formatMemoryBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0L)
    val gb = 1024.0 * 1024.0 * 1024.0
    val mb = 1024.0 * 1024.0
    val kb = 1024.0
    return when {
        safe >= gb -> String.format("%.1f GB", safe / gb)
        safe >= mb -> String.format("%.0f MB", safe / mb)
        safe >= kb -> String.format("%.0f KB", safe / kb)
        else -> "$safe B"
    }
}

/** Package id of the WeChat app the "open WeChat" card launches. */
internal const val ASSISTANT_HOME_WECHAT_PACKAGE = "com.tencent.mm"
