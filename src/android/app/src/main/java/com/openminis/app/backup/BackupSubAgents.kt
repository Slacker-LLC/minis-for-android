package com.openminis.app.backup

import com.openminis.app.data.model.ModelBinding
import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.data.model.ThinkingLevel
import java.time.Instant
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import kotlinx.serialization.Serializable

/**
 * One custom sub agent in `data/sub_agents.jsonl` (envelope type `SubAgentV1`), carried inside the
 * PROVIDERS category the way custom thinking rules are, so the set of backup categories does not change.
 *
 * The field names follow the upstream app's record so a package from there is readable here (its
 * `modelGroupId` is ignored: groups do not exist in this app, so such an agent restores as Auto). The extra
 * [modelEntryId] carries this app's own model pin; the providers restore keeps entry ids, so it stays valid
 * within one package. Fields without defaults are the ones the upstream decoder requires, and
 * `encodeDefaults` is off, so a default here would drop, say, an empty `instructions`.
 */
@Serializable
data class BackupSubAgentRecord(
    val id: String,
    val name: String,
    val subAgentDescription: String,
    val instructions: String,
    val modelGroupId: String? = null,
    val modelEntryId: String? = null,
    val sortOrder: Int,
    /** ISO-8601, whole seconds, UTC (`2026-09-28T12:00:00Z`). */
    val updatedAt: String,
    /** Lower-case ThinkingLevel name (`"high"`); absent = not set. */
    val thinkingLevelOverride: String? = null,
)

internal object BackupSubAgentMapping {

    const val FILE_BASE = "sub_agents"
    const val RECORD_TYPE = "SubAgentV1"

    /**
     * The built-in is never written: it ships with the app and its name and description are canonical, so an
     * old package's copy must not overwrite a newer build's.
     */
    fun exportable(roster: List<SubAgentDefinition>): List<SubAgentDefinition> =
        roster.filter { !it.isBuiltIn && it.id != SubAgentDefinition.BUILT_IN_ID }

    fun toRecord(def: SubAgentDefinition): BackupSubAgentRecord = BackupSubAgentRecord(
        id = def.id,
        name = def.name,
        subAgentDescription = def.description,
        instructions = def.instructions,
        modelEntryId = def.pinnedEntryId,
        sortOrder = def.sortOrder,
        // Whole seconds: a strict ISO-8601 decoder rejects fractional seconds, and one undecodable date
        // fails the whole record.
        updatedAt = Instant.ofEpochMilli(def.updatedAt).truncatedTo(ChronoUnit.SECONDS).toString(),
        thinkingLevelOverride = def.thinkingLevelOverride?.name?.lowercase(),
    )

    /**
     * Null for a record that must not be restored: the built-in, or one without a name (the roster matches on
     * names). An unreadable date becomes 0 = older than anything local, so it can add a new agent but never
     * overwrite one the user has.
     */
    fun fromRecord(r: BackupSubAgentRecord): SubAgentDefinition? {
        if (r.id.isBlank() || r.id == SubAgentDefinition.BUILT_IN_ID || r.name.isBlank()) return null
        return SubAgentDefinition(
            id = r.id,
            name = r.name,
            description = r.subAgentDescription,
            instructions = r.instructions,
            modelBinding = r.modelEntryId?.takeIf { it.isNotBlank() }?.let(ModelBinding::encodeEntry),
            thinkingLevelOverride = r.thinkingLevelOverride?.let(::parseThinking),
            isBuiltIn = false,
            sortOrder = r.sortOrder,
            updatedAt = parseMillis(r.updatedAt),
        )
    }

    /** Case-insensitive: "high" and "HIGH" both work; anything else means not set. */
    private fun parseThinking(raw: String): ThinkingLevel? =
        ThinkingLevel.entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }

    internal fun parseMillis(iso: String): Long =
        runCatching { Instant.parse(iso).toEpochMilli() }
            .recoverCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }
            .getOrDefault(0L)
}
