package com.openminis.app.provider

import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.rules.ModelRulesProvider

/** Declarative thinking ceilings are loaded from the bounded model-rules catalog. */
object ThinkingLevelCatalog {
    /** Null means no matching catalog rule; the caller applies the documented HIGH fallback. */
    fun declaredMaxLevel(modelId: String): ThinkingLevel? =
        ModelRulesProvider.capabilitiesFor(modelId).maxThinkingLevel
}

/**
 * "How high can this model's thinking go?" Resolution order is intentionally stable:
 *   1. supportsReasoning == false → OFF.
 *   2. A model-declared reasoningEffortValues set.
 *   3. The first matching model rule.
 *   4. HIGH for an unknown model, a broadly accepted reasoning tier.
 */
val LLMModel.catalogMaxThinkingLevel: ThinkingLevel
    get() {
        if (supportsReasoning == false) return ThinkingLevel.OFF
        selectableThinkingLevels.lastOrNull()?.let { return it }
        return ThinkingLevelCatalog.declaredMaxLevel(id) ?: ThinkingLevel.HIGH
    }

/** Empty means there is no explicit effort declaration; OFF remains a separate toggle. */
val LLMModel.selectableThinkingLevels: List<ThinkingLevel>
    get() {
        val declared = reasoningEffortValues
        if (declared.isNullOrEmpty()) return emptyList()
        val mapping = listOf(
            "low" to ThinkingLevel.LOW,
            "medium" to ThinkingLevel.MEDIUM,
            "high" to ThinkingLevel.HIGH,
            "xhigh" to ThinkingLevel.XHIGH,
            "max" to ThinkingLevel.MAX,
        )
        val declaredSet = declared.map { it.lowercase() }.toSet()
        return mapping.filter { it.first in declaredSet }.map { it.second }
    }

/** Entry overrides remain higher priority than either the built-in model or catalog rule. */
val ModelEntry.effectiveMaxThinkingLevel: ThinkingLevel
    get() = overrides.maxThinkingLevel ?: model.catalogMaxThinkingLevel
