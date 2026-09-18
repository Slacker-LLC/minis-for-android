package com.openminis.app.tools.skills

/**
 * [T-eta-skill-tools] Pure rules behind the model-facing skill tools: the bounds the
 * schema promises, how a skill is addressed, and when a read is refused.
 *
 * Ported from Eta `agent/model/AgentSkillToolCatalog.kt` (Mangi-11/Eta @ c15de97);
 * attribution in THIRD_PARTY_LICENSES.md. Bounds and defaults are Eta's (limit 1-200
 * default 50, maxChars 512-64000 default 16000) so a prompt written against either
 * implementation behaves the same.
 */
object SkillToolPolicy {

    const val DEFAULT_LIST_LIMIT = 50
    const val MAX_LIST_LIMIT = 200
    const val DEFAULT_MAX_CHARS = 16_000
    const val MIN_MAX_CHARS = 512
    const val MAX_MAX_CHARS = 64_000
    const val SKILLS_ROOT = "/var/minis/skills"

    /** Longest description echoed into a listing, matching the prompt fragment's cap. */
    const val MAX_LISTED_DESCRIPTION = 200

    data class SkillEntry(
        val id: String,
        val name: String,
        val description: String,
        val enabled: Boolean,
    ) {
        val path: String get() = skillMdPath(id)
    }

    data class ListOptions(val query: String?, val limit: Int)

    data class Truncated(val text: String, val truncated: Boolean, val originalChars: Int)

    sealed class ResourcePath {
        data class Ok(val path: String) : ResourcePath()
        data class Refused(val reason: String) : ResourcePath()
    }

    fun skillMdPath(id: String): String = "$SKILLS_ROOT/$id/SKILL.md"

    fun listOptions(queryRaw: String?, limitRaw: Int?): ListOptions = ListOptions(
        query = queryRaw?.trim()?.takeIf { it.isNotEmpty() }?.lowercase(),
        limit = (limitRaw ?: DEFAULT_LIST_LIMIT).coerceIn(1, MAX_LIST_LIMIT),
    )

    fun clampMaxChars(raw: Int?): Int = (raw ?: DEFAULT_MAX_CHARS).coerceIn(MIN_MAX_CHARS, MAX_MAX_CHARS)

    /** A query matches on id, name or description, case-insensitively. */
    fun matches(entry: SkillEntry, query: String?): Boolean {
        if (query == null) return true
        return entry.id.lowercase().contains(query) ||
            entry.name.lowercase().contains(query) ||
            entry.description.lowercase().contains(query)
    }

    fun formatList(entries: List<SkillEntry>, matchedTotal: Int, options: ListOptions): String {
        if (matchedTotal == 0) {
            return if (options.query == null) {
                "No skills are installed."
            } else {
                "No installed skill matches '${options.query}'."
            }
        }
        val header = buildString {
            append(entries.size).append(" of ").append(matchedTotal).append(" skill(s)")
            options.query?.let { append(" matching '").append(it).append("'") }
            append(":")
        }
        val body = entries.joinToString("\n") { entry ->
            val description = entry.description
                .take(MAX_LISTED_DESCRIPTION)
                .let { if (it.length < entry.description.length) "$it…" else it }
            buildString {
                append("- id: ").append(entry.id)
                append(" | name: ").append(entry.name)
                append(" | enabled: ").append(entry.enabled)
                append("\n  path: ").append(entry.path)
                if (description.isNotEmpty()) append("\n  description: ").append(description)
            }
        }
        val remaining = matchedTotal - entries.size
        val footer = if (remaining > 0) {
            "\n\n$remaining more match; raise limit (max $MAX_LIST_LIMIT) or narrow the query."
        } else {
            ""
        }
        return "$header\n$body$footer"
    }

    /**
     * Resolves what the model addressed: an exact id, an id in any case, a skill name,
     * or a SKILL.md path such as `/var/minis/skills/<id>/SKILL.md`.
     */
    fun resolveSkillId(request: String, entries: List<SkillEntry>): String? {
        val value = request.trim()
        if (value.isEmpty()) return null
        entries.firstOrNull { it.id == value }?.let { return it.id }
        entries.firstOrNull { it.id.equals(value, ignoreCase = true) }?.let { return it.id }
        entries.firstOrNull { it.name.equals(value, ignoreCase = true) }?.let { return it.id }
        if (value.endsWith("/SKILL.md", ignoreCase = true)) {
            val withoutSuffix = value.dropLast("/SKILL.md".length)
            val id = withoutSuffix.substringAfterLast('/')
            entries.firstOrNull { it.id == id }?.let { return it.id }
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) }?.let { return it.id }
        }
        return null
    }

    /** Truncation is explicit: the caller always learns how much of the file it saw. */
    fun truncate(text: String, maxChars: Int): Truncated {
        if (text.length <= maxChars) return Truncated(text, truncated = false, originalChars = text.length)
        return Truncated(
            text = text.take(maxChars) + "\n\n[truncated: $maxChars of ${text.length} characters shown]",
            truncated = true,
            originalChars = text.length,
        )
    }

    /**
     * A resource read stays inside the skill directory. The repository enforces the same
     * rule; refusing here as well keeps the failure legible and keeps the tool honest
     * about "never reads outside the Skill".
     */
    fun resourcePath(raw: String): ResourcePath {
        val value = raw.trim()
        if (value.isEmpty()) return ResourcePath.Refused("relativePath is required")
        if (value.length > 1_000) return ResourcePath.Refused("relativePath is longer than 1000 characters")
        if (value.startsWith("/")) return ResourcePath.Refused("relativePath must be relative to the skill root")
        if (value.contains('\\')) return ResourcePath.Refused("relativePath must use forward slashes only")
        if (value.any { it == '\u0000' || it < ' ' }) {
            return ResourcePath.Refused("relativePath contains a control character")
        }
        if (value.split('/').any { it == ".." }) {
            return ResourcePath.Refused("relativePath must not contain '..'")
        }
        return ResourcePath.Ok(value)
    }
}
