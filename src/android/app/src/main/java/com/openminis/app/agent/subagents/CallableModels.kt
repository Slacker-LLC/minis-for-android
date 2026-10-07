package com.openminis.app.agent.subagents

/**
 * A model the delegating agent may name in `subagent.model`: the name it emits ([handle]), the model
 * entry that name maps to, and a one-line description it reads when choosing.
 */
data class CallableModel(val handle: String, val entryId: String, val label: String, val summary: String)

/**
 * The models the user made available to the agent (Settings → Models the agent can use), turned into
 * names a model can emit. Pure, so naming and matching are unit-testable without the app.
 *
 * The same list feeds the `subagent.model` enum, the system-prompt section that describes it, and the
 * check that refuses anything else — so a name can never be advertised in one place and rejected in
 * another.
 */
object CallableModels {
    /**
     * At most this many are offered. The list is the user's own and normally short; the cap only keeps a
     * very long one from costing every turn more prompt than it is worth. The order is the user's.
     */
    const val MAX_OFFERED = 30

    /** One entry as the app sees it, before it is given a name. */
    data class Source(
        val entryId: String,
        val modelId: String,
        val displayName: String,
        val providerLabel: String,
        val contextWindow: Int?,
        val imageInput: Boolean,
        val reasoning: Boolean,
    )

    /**
     * Names each entry by its model id, which is what models and users already call it. When two entries
     * share a model id (the same model through two providers) both are qualified as `provider/model-id`,
     * the form `minis-model-use run --model` also accepts; anything still equal gets a `#n` suffix.
     * Comparison ignores case, because a model is likely to normalise case when it repeats a name.
     */
    fun from(sources: List<Source>): List<CallableModel> {
        val offered = sources.distinctBy { it.entryId }.take(MAX_OFFERED)
        val idCounts = offered.groupingBy { it.modelId.lowercase() }.eachCount()
        val used = HashSet<String>()
        return offered.map { s ->
            val base = if ((idCounts[s.modelId.lowercase()] ?: 0) > 1) "${s.providerLabel}/${s.modelId}" else s.modelId
            var handle = base
            var n = 2
            while (!used.add(handle.lowercase())) handle = "$base#${n++}"
            CallableModel(handle, s.entryId, s.displayName.ifBlank { s.modelId }, summary(s))
        }
    }

    /** Exact name match, ignoring case and surrounding space. No prefix or fuzzy matching: a near miss must not pick a different (possibly far more expensive) model. */
    fun match(name: String, models: List<CallableModel>): CallableModel? {
        val wanted = name.trim().lowercase()
        if (wanted.isEmpty()) return null
        return models.firstOrNull { it.handle.lowercase() == wanted }
    }

    private fun summary(s: Source): String = buildList {
        add(s.displayName.ifBlank { s.modelId })
        if (s.providerLabel.isNotBlank()) add("via ${s.providerLabel}")
        s.contextWindow?.takeIf { it > 0 }?.let { add("${it / 1000}K context") }
        if (s.imageInput) add("image input")
        if (s.reasoning) add("reasoning")
    }.joinToString(" · ")
}
