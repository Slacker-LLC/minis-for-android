package com.openminis.app.agent.subagents

import android.content.Context
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * [SubAgentJobStore] in app-private preferences. Stored text is bounded (a result keeps its first
 * [MAX_STORED_RESULT_CHARS] characters, a brief [MAX_STORED_BRIEF_CHARS]) so thirty jobs stay small, and a
 * damaged payload loads as "no history" instead of failing.
 */
class PrefsSubAgentJobStore(context: Context) : SubAgentJobStore {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun load(): List<SubAgentJob> = decode(prefs.getString(KEY, null))

    override fun save(jobs: List<SubAgentJob>) {
        prefs.edit().putString(KEY, encode(jobs)).apply()
    }

    companion object {
        private const val PREFS = "minis_sub_agent_jobs"
        private const val KEY = "jobs_json"
        const val MAX_STORED_RESULT_CHARS = 8_000
        const val MAX_STORED_BRIEF_CHARS = 20_000

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val serializer = ListSerializer(SubAgentJob.serializer())

        internal fun encode(jobs: List<SubAgentJob>): String = json.encodeToString(
            serializer,
            jobs.map {
                it.copy(
                    resultText = it.resultText?.take(MAX_STORED_RESULT_CHARS),
                    brief = it.brief?.take(MAX_STORED_BRIEF_CHARS),
                )
            },
        )

        internal fun decode(raw: String?): List<SubAgentJob> =
            if (raw.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(serializer, raw) }.getOrDefault(emptyList())
    }
}
