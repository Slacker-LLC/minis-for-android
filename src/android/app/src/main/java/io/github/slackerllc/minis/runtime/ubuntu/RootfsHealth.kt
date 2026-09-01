package io.github.slackerllc.minis.runtime.ubuntu

import org.json.JSONObject

enum class RootfsHealthCode {
    HEALTHY,
    MISSING,
    CORRUPT,
    INCOMPATIBLE,
    ROOT_UNAVAILABLE,
}

data class RootfsHealth(
    val code: RootfsHealthCode,
    val detail: String,
    val metadata: JSONObject? = null,
) {
    val healthy: Boolean get() = code == RootfsHealthCode.HEALTHY
}
