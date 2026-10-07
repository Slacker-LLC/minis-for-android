package com.openminis.app.ui.chat

import org.json.JSONObject

/** Adds up a session's stored usage rows into the Token Usage sheet's numbers. Moved out of [ChatViewModel] unchanged. */
internal object SessionTokenStatsCalc {
    /**
     * [usages] are the per-turn usage JSON rows; a malformed row is skipped. The last positive context
     * reading wins. The loop count approximates iterations as max(tool blocks, assistant messages),
     * matching iOS.
     */
    fun aggregate(usages: List<String>, messages: List<ChatMessage>): ChatViewModel.SessionTokenStats {
        var input = 0L
        var output = 0L
        var cacheRead = 0L
        var cacheWrite = 0L
        var context = 0
        for (json in usages) {
            try {
                val obj = JSONObject(json)
                input += obj.optLong("inputTokens", 0L)
                output += obj.optLong("outputTokens", 0L)
                cacheRead += obj.optLong("cacheReadTokens", 0L)
                cacheWrite += obj.optLong("cacheCreationTokens", 0L)
                val ctx = obj.optInt("latestContextTokens", 0)
                if (ctx > 0) context = ctx
            } catch (_: Exception) { /* skip malformed row */ }
        }
        val assistantCount = messages.count { it.role == "assistant" }
        val toolCalls = messages.filter { it.role == "assistant" }
            .sumOf { msg -> msg.toolBlocks.count { it.kind != "text" && it.kind != "info" } }
        val loops = maxOf(toolCalls, assistantCount)
        return ChatViewModel.SessionTokenStats(input, output, cacheRead, cacheWrite, context, loops)
    }
}
