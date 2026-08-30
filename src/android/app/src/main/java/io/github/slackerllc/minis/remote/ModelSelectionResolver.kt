package io.github.slackerllc.minis.remote

import io.github.slackerllc.minis.data.model.ProviderConfig

/**
 * Single model-identity resolver shared by the App and Web surfaces
 * (P-model-identity).
 *
 * BEFORE this class, session projections hard-coded `provider: "openminis"`
 * and used the display NAME as the model id, while `session.models` resolved
 * the real entry. The two surfaces therefore disagreed on what a session was
 * running. Everything that answers "which model is this session on" must go
 * through this resolver:
 *
 *   - session.list summary projections (`modelSelection`)
 *   - session.history tail projections (`modelSelection`)
 *   - session.models `current`
 *   - session.selectModel response
 *
 * Identity is: real provider instance label (as shown in the App's own model
 * picker), real model ENTRY id (the id `session.models` groups use — resolved
 * from the base model id so both sides reference the same row), real display
 * name, real reasoning capability and ceiling.
 *
 * Pure Kotlin (no Android imports) so JVM tests can pin resolution without a
 * device.
 */
object ModelSelectionResolver {

    /** Canonical wire identity of one selectable model. */
    data class Identity(
        val provider: String,
        val modelId: String,
        val displayName: String,
        val entryId: String = modelId,
    )

    /**
     * Resolve an entry for a session's persisted base model id.
     * `modelId` is the session row's `modelId` (base model id) or an entry
     * id. Returns null only when the config genuinely has no such entry.
     */
    fun resolve(cfg: ProviderConfig, modelId: String): Identity? {
        if (modelId.isBlank()) return null
        val entry = cfg.modelEntries.firstOrNull {
            it.id == modelId || it.baseModel.id == modelId
        } ?: return null
        val instance = cfg.instances.firstOrNull { it.id == entry.providerInstanceId }
        return Identity(
            provider = instance?.label ?: entry.providerInstanceId,
            modelId = entry.baseModel.id,
            displayName = entry.model.displayName,
            entryId = entry.id,
        )
    }

    /** Placeholder identity for a session with no resolved model (schema requires non-empty). */
    fun placeholder(): Identity = Identity(
        provider = "OpenMinis",
        modelId = "unconfigured",
        displayName = "未配置模型",
    )

    /** DSH wire selection: `{ provider, model, reasoningEffort? }` (client.js modelSelectionSchema). */
    fun toWire(identity: Identity, effort: String? = null): org.json.JSONObject =
        org.json.JSONObject().apply {
            put("provider", identity.provider)
            put("model", identity.entryId)
            if (effort != null && effort.isNotEmpty()) put("reasoningEffort", effort)
        }
}
