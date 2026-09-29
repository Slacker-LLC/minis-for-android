package com.openminis.app.data.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Parsed form of the legacy session model_binding payload. */
sealed interface ModelBinding {
    data class Group(val groupId: String, val lastEntryId: String? = null) : ModelBinding
    data class Entry(val entryId: String) : ModelBinding

    companion object {
        /** Returns null for malformed, incomplete, or unknown binding payloads. */
        fun parse(raw: String?): ModelBinding? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val objectValue = Json.parseToJsonElement(raw).jsonObject
                fun field(name: String): String? =
                    objectValue[name]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotEmpty)

                when (field("type")) {
                    "group" -> field("groupId")?.let { Group(it, field("lastEntryId")) }
                    "entry" -> field("entryId")?.let(::Entry)
                    else -> null
                }
            }.getOrNull()
        }
    }
}
