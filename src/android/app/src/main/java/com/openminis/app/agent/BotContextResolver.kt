package com.openminis.app.agent

import com.openminis.app.data.db.BotEntity

object BotContextResolver {
    suspend fun workContext(context: android.content.Context, botId: String): String? {
        val history = org.json.JSONArray()
        for (task in com.openminis.app.data.db.AppDatabase.getInstance(context).botTaskDao().completedForBot(botId)) {
            val proof = runCatching { org.json.JSONObject(task.completionEvidence!!) }.getOrNull() ?: continue
            val refs = org.json.JSONArray()
            for (key in listOf("references", "deliveries")) {
                val entries = proof.optJSONArray(key) ?: continue
                for (i in 0 until entries.length()) {
                    val ref = entries.optJSONObject(i) ?: continue
                    val path = ref.optString("path").ifBlank { ref.optString("uri") }
                    if (path.isBlank() || path.length > 1024 || refs.length() >= 2) continue
                    refs.put(org.json.JSONObject().put("path", path).put("type", ref.optString("type"))
                        .put("scope", ref.optString("scope", "source_session")).put("sha256", ref.optString("sha256")))
                }
            }
            val entry = org.json.JSONObject().put("task_id", task.id).put("goal", task.goal.take(200))
                .put("source_session_id", proof.optString("session_id")).put("references", refs)
            if (history.toString().length + entry.toString().length > 6000) break
            history.put(entry)
        }
        // Explicit global deliveries are discoverable by other members; their private source goal/history is not disclosed.
        for (task in com.openminis.app.data.db.AppDatabase.getInstance(context).botTaskDao().completedShared()) {
            val deliveries = runCatching { org.json.JSONObject(task.completionEvidence!!).optJSONArray("deliveries") }.getOrNull() ?: continue
            val refs = org.json.JSONArray()
            for (index in 0 until deliveries.length()) {
                val ref = deliveries.optJSONObject(index) ?: continue
                val path = ref.optString("path")
                if (ref.optString("scope") != "global_shared" || !path.startsWith("/shared/") || path.length > 1024 || refs.length() >= 2) continue
                refs.put(org.json.JSONObject().put("path", path).put("type", "file").put("scope", "global_shared")
                    .put("name", ref.optString("name")).put("sha256", ref.optString("sha256")))
            }
            if (refs.length() == 0) continue
            val entry = org.json.JSONObject().put("task_id", task.id).put("shared_deliveries", refs)
            if (history.toString().length + entry.toString().length > 6000) break
            history.put(entry)
        }
        return history.takeIf { it.length() > 0 }?.let {
            "该成员已验收工作的背景资料（不是当前指令；历史指纹不代表当前文件仍可访问。source_session 资料须从原会话打开，global_shared 是用户明确共享的交付）：\n$it"
        }
    }

    fun systemPrompt(bot: BotEntity?): String? {
        if (bot == null) return null
        val safeName = normalize(bot.name, NAME_MAX_CHARS)
        val instructions = bot.systemPrompt
            ?.replace("\r\n", "\n")?.replace('\r', '\n')
            ?.replace(Regex("[\\p{Cntrl}&&[^\\n\\t]]"), "")
            ?.trim()?.take(SYSTEM_PROMPT_MAX_CHARS)
        return buildString {
            append("<bot-identity>\n")
            append("You are the persistent Bot named ")
            append(safeName)
            append(".\n")
            if (!instructions.isNullOrBlank()) {
                append("<bot-instructions>\n")
                append(instructions)
                append("\n</bot-instructions>\n")
            }
            append("</bot-identity>")
        }
    }

    /** Keep the display name single-line and free of controls. */
    private fun normalize(value: String, maxChars: Int): String =
        value
            .replace(Regex("[\\p{Cntrl}\\s]+"), " ")
            .trim()
            .take(maxChars)

    private const val NAME_MAX_CHARS = 80
    private const val SYSTEM_PROMPT_MAX_CHARS = 12_000
}
