package com.openminis.app.agent.subagents

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.data.model.SubAgentRoster
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * The user's sub agent roster and the master switch, in app-private preferences.
 *
 * The roster is normalized on every load and save ([SubAgentRoster.normalize]): stored data can be
 * damaged or hand-edited, and a bad roster must never block startup or lose the built-in.
 */
object SubAgentStore {
    private const val PREFS = "minis_sub_agents"
    private const val KEY_ROSTER = "roster_json"
    private const val KEY_ENABLED = "enabled"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val listSerializer = ListSerializer(SubAgentDefinition.serializer())

    private val _roster = MutableStateFlow(SubAgentRoster.normalize(emptyList()))
    val roster: StateFlow<List<SubAgentDefinition>> = _roster.asStateFlow()

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    @Volatile private var prefs: SharedPreferences? = null

    /** Loads the stored roster; safe to call more than once. */
    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _roster.value = decode(p.getString(KEY_ROSTER, null))
        _enabled.value = p.getBoolean(KEY_ENABLED, true)
    }

    /** Replaces the roster (normalized) and persists it. Returns what was actually stored. */
    fun save(definitions: List<SubAgentDefinition>): List<SubAgentDefinition> {
        val normalized = SubAgentRoster.normalize(definitions)
        _roster.value = normalized
        prefs?.edit()?.putString(KEY_ROSTER, encode(normalized))?.apply()
        return normalized
    }

    fun setEnabled(enabled: Boolean) {
        _enabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
    }

    /** The enabled agents in disclosure order, for the prompt and the tool schema. */
    fun currentRoster(): List<SubAgentDefinition> = _roster.value

    internal fun encode(definitions: List<SubAgentDefinition>): String =
        json.encodeToString(listSerializer, definitions)

    /** Total: a missing, empty or malformed payload yields the default roster instead of throwing. */
    internal fun decode(raw: String?): List<SubAgentDefinition> {
        val decoded = if (raw.isNullOrBlank()) emptyList() else runCatching {
            json.decodeFromString(listSerializer, raw)
        }.getOrDefault(emptyList())
        return SubAgentRoster.normalize(decoded)
    }
}
