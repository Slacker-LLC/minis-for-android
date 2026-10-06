package com.openminis.app.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Hard limits on the sub agent roster.
 *
 * These are not defensive "just in case" numbers: the roster is injected into the main
 * conversation's system prompt on every turn, so the count and the description length ARE the
 * fixed per-request cost. Bounding the input is what removes the need for a runtime token warning
 * — a user cannot configure a roster that blows the budget. At the maximum (10 x (40 + 200)
 * chars) the roster is roughly 800 tokens.
 *
 * Adapted from OpenMinis 1.14 (`SubAgentLimits`, [T-sub-agents-v1]); see PROVENANCE.md.
 */
object SubAgentLimits {
    /** Including the built-in one. */
    const val MAX_COUNT = 10
    const val NAME_MAX_LENGTH = 40

    /** The only free text that reaches the main conversation. */
    const val DESCRIPTION_MAX_LENGTH = 200

    /** Child-session only; bounded so one definition cannot eat the child's context. */
    const val INSTRUCTIONS_MAX_LENGTH = 4000
}

/**
 * One named sub agent the main model can delegate to by name.
 *
 * Adapted from OpenMinis 1.14 (`SubAgentDefinition`). Upstream pins a sub agent to a model *group*;
 * this app replaced model groups with fixed model slots, so the pin here is a single model entry
 * ([modelBinding], the same `{"type":"entry",...}` payload a Bot uses). `null` means Auto: the
 * delegating model picks with `model_choice` (its own model, the main slot, or the light slot).
 */
@Serializable
data class SubAgentDefinition(
    val id: String = UUID.randomUUID().toString(),
    /**
     * The wire identifier, NOT a label: the `enum` of `subagent.agent`, the value the model has
     * to emit, and the key [SubAgentRoster.resolve] matches on. It is deliberately never localized —
     * localizing it would put the UI language into the tool schema and break every stored reference
     * the moment the user switched language.
     */
    var name: String,
    /**
     * What the main model reads to decide whether to pick this agent. Bounded because it is the only
     * part that costs main-conversation tokens.
     */
    var description: String,
    /** Appended to the child's brief. Empty = nothing appended. */
    var instructions: String = "",
    /** A pinned model entry (`ModelBinding.encodeEntry`), or null = Auto. */
    var modelBinding: String? = null,
    /** Reasoning intensity for this agent's runs; null = inherit what the child session defaults to. */
    var thinkingLevelOverride: ThinkingLevel? = null,
    val isBuiltIn: Boolean = false,
    var sortOrder: Int = 0,
    /** Epoch millis. */
    var updatedAt: Long = System.currentTimeMillis(),
) {
    /** The pinned model entry id, or null for Auto (or a binding this build cannot read). */
    val pinnedEntryId: String?
        get() = (ModelBinding.parse(modelBinding) as? ModelBinding.Entry)?.entryId

    /**
     * Field-level clamp applied on load and on save. Synced or hand-edited data can exceed the UI
     * limits, so the limits alone are not enough — see [SubAgentRoster.normalize].
     */
    fun clamped(): SubAgentDefinition = copy(
        name = name.trim().take(SubAgentLimits.NAME_MAX_LENGTH),
        description = description.take(SubAgentLimits.DESCRIPTION_MAX_LENGTH),
        instructions = instructions.take(SubAgentLimits.INSTRUCTIONS_MAX_LENGTH),
    )

    /** Whether any field was over its limit — used only to decide whether to log. */
    val exceedsLimits: Boolean
        get() = name.length > SubAgentLimits.NAME_MAX_LENGTH ||
            description.length > SubAgentLimits.DESCRIPTION_MAX_LENGTH ||
            instructions.length > SubAgentLimits.INSTRUCTIONS_MAX_LENGTH

    companion object {
        /** The built-in definition's fixed id; the id is what code and persisted payloads key on. */
        const val BUILT_IN_ID = "builtin.general"

        /**
         * The sub agent tool's wire name: the existing `subagent` tool, so transcripts, permission policy,
         * UI grouping and prompts keep working. The schema behind the name depends on the Settings switch.
         */
        const val TOOL_NAME = "subagent"

        /** The built-in's stored name, deliberately NOT localized. See [name]. */
        const val BUILT_IN_NAME = "General Sub Agent"

        /** Also not localized: this goes into the system-prompt roster. */
        const val BUILT_IN_DESCRIPTION =
            "Open-ended work that needs its own tool loop: exploring a codebase or the web over many rounds, " +
                "digesting bulk output into a conclusion, or running independent branches in parallel."

        /** The built-in general sub agent, inserted by normalize when absent. */
        fun makeBuiltIn(sortOrder: Int = 0): SubAgentDefinition = SubAgentDefinition(
            id = BUILT_IN_ID,
            name = BUILT_IN_NAME,
            description = BUILT_IN_DESCRIPTION,
            instructions = "",
            modelBinding = null,
            isBuiltIn = true,
            sortOrder = sortOrder,
        )
    }
}

/**
 * Roster-level rules: the built-in must exist, the list is bounded, ordering is the disclosure
 * order. Free functions on the list rather than a store type, so the load path, the backup merge
 * and the tests share one implementation.
 */
object SubAgentRoster {

    /**
     * Normalise a decoded roster: guarantee the built-in, clamp every field, bound the count, drop
     * unaddressable or duplicate entries, and renumber [SubAgentDefinition.sortOrder] densely from 0.
     *
     * NEVER throws and never drops the built-in: this runs on stored data that may be damaged or
     * hand-edited, and a bad roster must not be able to block startup. Anything discarded is logged.
     */
    fun normalize(
        input: List<SubAgentDefinition>,
        log: ((String) -> Unit)? = null,
    ): List<SubAgentDefinition> {
        val list = input.toMutableList()

        // The built-in is pinned to the front regardless of its stored sortOrder.
        val builtInIndex = list.indexOfFirst { it.id == SubAgentDefinition.BUILT_IN_ID }
        var builtIn: SubAgentDefinition
        if (builtInIndex >= 0) {
            // The id is what identifies the built-in; a stored row that carries it without the flag
            // (damaged or hand-edited) must not lose its delete protection.
            builtIn = list.removeAt(builtInIndex).copy(isBuiltIn = true)
            // Its name and description are canonical English (the tool-schema enum and the roster
            // the model reads); restore them on every load. The user's own fields — model,
            // instructions, reasoning level — are untouched.
            if (builtIn.name != SubAgentDefinition.BUILT_IN_NAME ||
                builtIn.description != SubAgentDefinition.BUILT_IN_DESCRIPTION
            ) {
                log?.invoke("[SubAgents] restoring the built-in's canonical name/description")
                builtIn = builtIn.copy(
                    name = SubAgentDefinition.BUILT_IN_NAME,
                    description = SubAgentDefinition.BUILT_IN_DESCRIPTION,
                )
            }
        } else {
            builtIn = SubAgentDefinition.makeBuiltIn()
            log?.invoke("[SubAgents] built-in definition missing — reinserting")
        }

        // Custom entries keep the user's order; ties break by id so the result is deterministic.
        list.sortWith(compareBy({ it.sortOrder }, { it.id }))

        val allowedCustom = maxOf(0, SubAgentLimits.MAX_COUNT - 1)
        var custom: List<SubAgentDefinition> = list
        if (custom.size > allowedCustom) {
            val dropped = custom.drop(allowedCustom).map { it.name }
            log?.invoke(
                "[SubAgents] roster over the limit — keeping $allowedCustom of ${custom.size} " +
                    "custom definitions, dropping: ${dropped.joinToString(", ")}",
            )
            custom = custom.take(allowedCustom)
        }

        if (builtIn.exceedsLimits || custom.any { it.exceedsLimits }) {
            log?.invoke("[SubAgents] one or more definitions exceeded field limits — truncating")
        }

        val out = mutableListOf(builtIn.clamped().copy(sortOrder = 0))
        val seenNames = mutableSetOf(nameKey(builtIn.clamped().name))
        var next = 1
        for (def in custom) {
            // A custom definition must not claim the built-in flag or its id: isBuiltIn drives
            // "cannot delete" in the UI.
            if (def.isBuiltIn || def.id == SubAgentDefinition.BUILT_IN_ID) continue
            // A nameless definition cannot be addressed: the name IS the enum value the model emits
            // and the key resolve() matches on.
            if (def.name.isBlank()) continue
            // Two rows sharing a name make resolution ambiguous. The comparison is on the name as it
            // will be STORED: two 41-character names that differ only in the last character are the
            // same name once clamped to 40, and keeping both would give one of them no way to be picked.
            val stored = def.clamped()
            if (!seenNames.add(nameKey(stored.name))) continue
            out.add(stored.copy(sortOrder = next))
            next += 1
        }
        return out
    }

    /**
     * The definition a delegation should run under. Matching is case- and diacritic-insensitive and
     * whitespace-trimmed because the name comes back from a model. A null/blank name = the built-in.
     * A name that matches nothing returns null, which the caller turns into `unknown_agent`.
     */
    fun resolve(name: String?, roster: List<SubAgentDefinition>): SubAgentDefinition? {
        val raw = name?.let { nameKey(it) }
        if (raw.isNullOrEmpty()) {
            return roster.firstOrNull { it.id == SubAgentDefinition.BUILT_IN_ID } ?: roster.firstOrNull()
        }
        return roster.firstOrNull { nameKey(it.name) == raw }
    }

    /** The comparison form of a name: trimmed, case-folded and diacritic-folded. */
    fun nameKey(s: String): String =
        java.text.Normalizer.normalize(s.trim(), java.text.Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
            .lowercase()

    private val COMBINING_MARKS = Regex("\\p{Mn}+")

    /** Outcome of [mergeBackup]: the roster to save plus the report counts. */
    data class BackupMerge(val roster: List<SubAgentDefinition>, val written: Int, val skipped: Int)

    /**
     * Folds a backup package's custom sub agents into the local roster:
     *  - an id not known locally is added, unless its NAME matches a local agent: the model picks agents
     *    by name, so two entries it cannot tell apart would make one unreachable and the local one stays;
     *  - a known id is replaced only when the package's copy is newer, so restoring an old package cannot
     *    roll back an agent edited since;
     *  - the built-in is never touched and nothing is deleted.
     * The result is normalized (count bound, dense sortOrder).
     */
    fun mergeBackup(
        local: List<SubAgentDefinition>,
        incoming: List<SubAgentDefinition>,
        log: ((String) -> Unit)? = null,
    ): BackupMerge {
        val out = local.toMutableList()
        var written = 0
        var skipped = 0
        for (r in incoming) {
            if (r.isBuiltIn || r.id == SubAgentDefinition.BUILT_IN_ID) { skipped++; continue }
            val at = out.indexOfFirst { it.id == r.id }
            if (at >= 0) {
                if (r.updatedAt > out[at].updatedAt) { out[at] = r.copy(isBuiltIn = false); written++ } else skipped++
                continue
            }
            // Compared as it will be stored (clamped), so the merge result survives normalize unchanged.
            val storedName = r.clamped().name
            if (out.any { nameKey(it.clamped().name) == nameKey(storedName) }) {
                log?.invoke("backup sub agent '${r.name}' skipped: a local agent already has that name")
                skipped++
                continue
            }
            out.add(r.copy(isBuiltIn = false))
            written++
        }
        return BackupMerge(normalize(out, log), written, skipped)
    }
}
