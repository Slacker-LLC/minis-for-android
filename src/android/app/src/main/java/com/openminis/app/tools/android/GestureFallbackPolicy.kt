package com.openminis.app.tools.android

/**
 * Only a gesture that provably never reached the system may be replaced by another
 * implementation. Minis never replays through Root, so this gates the in-app fallback.
 */
object GestureFallbackPolicy {
    fun mayFallbackToRoot(errorCode: String): Boolean =
        errorCode == "GESTURE_NOT_DISPATCHED"
}
