package com.openminis.app.data.model

/**
 * Immutable identity of the model/provider that produced one message.
 *
 * The session's selected model is mutable: switching models or automatic
 * fallback rewrites it. Usage history therefore must carry its own identity
 * instead of resolving the model from the session later.
 */
data class ModelAttributionSnapshot(
    /** Stable model id, including the concrete model version when provided. */
    val modelId: String,
    /** Effective display name at request time, including entry overrides. */
    val displayName: String,
    /** ProviderType.name, never a localized display label. */
    val providerTypeRaw: String,
    /** Provider instance id, used to distinguish duplicate model ids. */
    val providerInstanceId: String?,
)
