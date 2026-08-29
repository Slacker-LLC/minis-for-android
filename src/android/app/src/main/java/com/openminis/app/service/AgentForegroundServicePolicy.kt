package com.openminis.app.service

import android.app.Service

/**
 * Narrow lifecycle policy for the interactive/headless agent foreground service.
 *
 * Chat-screen presence is deliberately not a reason to hold a foreground
 * service. The service exists only while at least one agent turn is actively
 * streaming/executing. If Android kills the process, the service is not
 * recreated without a new explicit agent start; persisted chat/checkpoint state
 * is responsible for safe recovery instead of a sticky process-residency hack.
 */
internal object AgentForegroundServicePolicy {

    fun shouldRun(activeSessionCount: Int, presentSessionCount: Int): Boolean {
        require(activeSessionCount >= 0) { "activeSessionCount must be non-negative" }
        require(presentSessionCount >= 0) { "presentSessionCount must be non-negative" }
        return activeSessionCount > 0
    }

    val restartMode: Int
        get() = Service.START_NOT_STICKY
}
