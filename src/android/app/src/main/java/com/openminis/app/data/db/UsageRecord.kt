package com.openminis.app.data.db

/** Raw usage record from joined messages + sessions query. */
data class UsageRecord(
    /**
     * [T-android-usage-orphan-rows] Nullable because `allUsageRecords` uses a
     * LEFT JOIN (GH#168): a message whose `sessions` row is missing still
     * carries real, already-billed token usage and must be counted, but it has
     * no session to read a model id from. Room would throw on the NULL if this
     * stayed non-null. Callers group these under "Unknown".
     */
    val modelId: String?,
    /** Display name captured at the time the message was produced. */
    val modelDisplayName: String?,
    /** ProviderType raw name captured at the time the message was produced. */
    val providerType: String?,
    /** Provider instance id captured at the time the message was produced. */
    val providerInstanceId: String?,
    /** True when the row has immutable message-level attribution. */
    val hasSnapshot: Boolean,
    val tokenUsage: String,
    val createdAt: Long,
    val sessionId: String,
)
