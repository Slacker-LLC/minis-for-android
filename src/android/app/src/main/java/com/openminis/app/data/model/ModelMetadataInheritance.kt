package com.openminis.app.data.model

/**
 * A relay or self-hosted endpoint serves models under the same ids as their
 * vendors, but its `/models` list carries none of the vendor's metadata, and
 * models.dev only knows ids it has shipped. Without this, the same model is
 * 1M on one provider entry and "unknown, assume 128K" on another, which skews
 * the auto-compaction threshold and hides the thinking controls.
 *
 * Fills only the fields the entry leaves unset (null); a value the provider or
 * the user already supplied is never replaced.
 */
internal fun LLMModel.inheritMissingMetadata(donors: List<LLMModel>): LLMModel {
    if (donors.isEmpty()) return this
    val key = metadataKey(id)
    val peers = donors.filter { metadataKey(it.id) == key }
    if (peers.isEmpty()) return this

    // Peers can disagree (1,000,000 vs 1,048,576; or a gateway that truncates). Take the
    // most common positive value and, on a tie, the smaller one: a low estimate compacts a
    // little early, a high one overflows the real window.
    fun commonPositive(values: List<Int?>): Int? = values.filterNotNull().filter { it > 0 }
        .groupingBy { it }.eachCount()
        .entries.sortedWith(compareByDescending<Map.Entry<Int, Int>> { it.value }.thenBy { it.key })
        .firstOrNull()?.key

    val effortSource = peers.firstOrNull { !it.reasoningEffortValues.isNullOrEmpty() }
    return copy(
        contextWindow = contextWindow ?: commonPositive(peers.map { it.contextWindow }),
        maxOutputTokens = maxOutputTokens ?: commonPositive(peers.map { it.maxOutputTokens }),
        supportsReasoning = supportsReasoning ?: peers.firstNotNullOfOrNull { it.supportsReasoning },
        reasoningEffortValues = reasoningEffortValues?.takeIf { it.isNotEmpty() } ?: effortSource?.reasoningEffortValues,
        declaresNoEffortTiers = declaresNoEffortTiers
            ?: if (effortSource == null) peers.firstNotNullOfOrNull { it.declaresNoEffortTiers } else null,
        interleavedReasoningField = interleavedReasoningField
            ?: peers.firstNotNullOfOrNull { it.interleavedReasoningField },
    )
}

/** `vendor/Model-Name` and `model-name` are the same model. */
private fun metadataKey(id: String): String = id.trim().lowercase().substringAfterLast('/')

/**
 * True when the window comes from real metadata (the entry itself or the model
 * rules) rather than the id-based guess in [LLMModel.contextWindowTokens].
 */
val LLMModel.hasKnownContextWindow: Boolean
    get() = (contextWindow ?: 0) > 0 ||
        (com.openminis.app.provider.rules.ModelRulesProvider.capabilitiesFor(id).contextWindow ?: 0) > 0
