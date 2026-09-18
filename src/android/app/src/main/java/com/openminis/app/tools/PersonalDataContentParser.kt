package com.openminis.app.tools

import org.json.JSONObject

/**
 * [T-eta-xposed-groups] Reading `content query` output: rows, the columns that were asked for, and
 * the difference between "nothing matched" and "the provider refused".
 *
 * Ported from Eta `agent/tool/AgentPersonalDataTools.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. Both properties matter and both are easy to get wrong: a provider
 * exception printed to stderr must never be read as an empty result, and a row is split by the
 * columns that were requested, so a message body containing commas stays one value.
 */
object PersonalDataContentParser {

    fun hasProviderFailure(stdout: String, stderr: String): Boolean =
        sequenceOf(stdout, stderr).any { output ->
            output.contains("Error while accessing provider:") ||
                output.contains("java.lang.IllegalArgumentException:") ||
                output.contains("java.lang.SecurityException:")
        }

    fun parseRows(source: String, columns: List<String>): List<JSONObject> =
        source.lineSequence()
            .filter { it.startsWith("Row:") }
            .map { line ->
                JSONObject().also { row ->
                    columns.forEach { column -> value(line, column, columns)?.let { row.put(column, it) } }
                }
            }
            .toList()

    private fun value(line: String, column: String, columns: List<String>): String? {
        val following = columns.filterNot { it == column }.joinToString("|") { Regex.escape(it) }
        return Regex("(?:^|,\\s*|\\s)${Regex.escape(column)}=(.*?)(?=,\\s*(?:$following)=|$)")
            .find(line)
            ?.groupValues
            ?.get(1)
            ?.takeUnless { it == "null" }
    }
}
