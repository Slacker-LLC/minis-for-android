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
) {
    val healthy: Boolean get() = code == RootfsHealthCode.HEALTHY
}
