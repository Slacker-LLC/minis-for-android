package com.openminis.app.ui.navigation

internal fun resolveAutoLaunchSessionId(
    latestSessionId: String?,
    latestSessionUpdatedAt: Long?,
    nowMillis: Long,
    autoThresholdMs: Long,
    newDraftSessionId: () -> String,
): String {
    if (latestSessionId == null || latestSessionUpdatedAt == null) {
        return newDraftSessionId()
    }
    val fresh = nowMillis - latestSessionUpdatedAt < autoThresholdMs
    return if (fresh) latestSessionId else newDraftSessionId()
}
