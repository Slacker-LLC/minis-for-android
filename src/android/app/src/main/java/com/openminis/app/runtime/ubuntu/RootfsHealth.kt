package com.openminis.app.runtime.ubuntu

import org.json.JSONObject

enum class RootfsHealthCode {
    HEALTHY,
    MISSING,
    CORRUPT,
    INCOMPATIBLE,
    ROOT_UNAVAILABLE,
    UNKNOWN,
}

data class RootfsHealth(
    val code: RootfsHealthCode,
    val detail: String,
    val metadata: JSONObject? = null,
    val provisioned: Boolean = false,
    /** Broker-reported logical size in bytes; null means size was unavailable. */
    val sizeBytes: Long? = null,
) {
    val healthy: Boolean get() = code == RootfsHealthCode.HEALTHY
}
