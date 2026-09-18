package com.openminis.app.tools.skills

import org.json.JSONObject

/**
 * [T-eta-skill-tools] Pure rules for installing skills from a public GitHub repository:
 * how a repository is named, which directories count as skills, and where the pinned
 * fetches point.
 *
 * Ported from Eta `agent/model/AgentSkillToolCatalog.kt` and the discovery/install path
 * behind it (Mangi-11/Eta @ c15de97); attribution in THIRD_PARTY_LICENSES.md. The
 * contract Eta states is kept: inspection is read-only and returns the commitSha, and
 * an install must name a directory that inspection returned, so the content cannot move
 * under the caller between the two calls.
 *
 * Everything here is string/JSON work with no network and no Android types, so the
 * rules are unit-testable without a device.
 */
object SkillSourcePolicy {

    /** How many candidate directories an inspection reports before it stops listing. */
    const val MAX_CANDIDATES = 200

    const val MAX_PATH_LENGTH = 1_000

    data class RepoRef(val owner: String, val repo: String, val ref: String?, val path: String?)

    data class Candidates(val paths: List<String>, val truncated: Boolean)

    /**
     * Accepts `owner/repo`, `github.com/owner/repo[/tree|blob/<ref>/<path>]` and
     * `raw.githubusercontent.com/owner/repo/<ref>/<path>` — with or without a scheme.
     * Returns null for anything else, including other hosts, so a typo cannot send the
     * request somewhere unintended.
     */
    fun parseRepository(raw: String): RepoRef? {
        val trimmed = raw.trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')
        if (trimmed.isEmpty()) return null
        val parts = trimmed.split('/').filter { it.isNotEmpty() }
        if (parts.size < 2) return null
        return when (val host = parts[0].lowercase()) {
            "github.com", "www.github.com" -> fromGitHubParts(parts.drop(1))
            "raw.githubusercontent.com" -> {
                val rest = parts.drop(1)
                if (rest.size < 3) null
                else RepoRef(
                    owner = rest[0],
                    repo = rest[1].removeSuffix(".git"),
                    ref = rest[2],
                    path = stripSkillMd(rest.drop(3).joinToString("/")),
                )
            }
            else -> {
                // `owner/repo` shorthand, but never a hostname: `example.com/repo` must
                // not be read as owner=example.com.
                if (host.contains('.')) return null
                val owner = parts[0]
                val repo = parts[1].removeSuffix(".git")
                if (owner.isEmpty() || repo.isEmpty()) return null
                if (parts.size >= 4 && (parts[2] == "tree" || parts[2] == "blob")) {
                    RepoRef(owner, repo, parts[3], stripSkillMd(parts.drop(4).joinToString("/")))
                } else if (parts.size > 2 && parts[2] != "tree" && parts[2] != "blob") {
                    RepoRef(owner, repo, null, stripSkillMd(parts.drop(2).joinToString("/")))
                } else {
                    RepoRef(owner, repo, null, null)
                }
            }
        }
    }

    private fun fromGitHubParts(rest: List<String>): RepoRef? {
        if (rest.size < 2) return null
        val owner = rest[0]
        val repo = rest[1].removeSuffix(".git")
        if (owner.isEmpty() || repo.isEmpty()) return null
        if (rest.size >= 4 && (rest[2] == "tree" || rest[2] == "blob")) {
            return RepoRef(owner, repo, rest[3], stripSkillMd(rest.drop(4).joinToString("/")))
        }
        if (rest.size > 2 && rest[2] != "tree" && rest[2] != "blob") {
            return RepoRef(owner, repo, null, stripSkillMd(rest.drop(2).joinToString("/")))
        }
        return RepoRef(owner, repo, null, null)
    }

    /** A `SKILL.md` path names its directory; anything else is already a directory. */
    private fun stripSkillMd(path: String): String? {
        val value = path.trim('/')
        if (value.isEmpty()) return null
        return if (value.endsWith("/SKILL.md", ignoreCase = true)) value.dropLast("/SKILL.md".length)
        else if (value.equals("SKILL.md", ignoreCase = true)) null
        else value
    }

    /**
     * A repository-relative directory the caller asked to narrow to, or null when the
     * value is unusable (absolute, `..`, backslash, control character, too long).
     */
    fun safeRelativeDirectory(raw: String): String? {
        val value = raw.trim().removePrefix("./").trimEnd('/')
        if (value.isEmpty()) return null
        if (value.length > MAX_PATH_LENGTH) return null
        if (value.startsWith("/") || value.contains('\\')) return null
        if (value.any { it == '\u0000' || it < ' ' }) return null
        if (value.split('/').any { it == ".." }) return null
        return value
    }

    /**
     * Directories that contain a SKILL.md, from a GitHub `git/trees?recursive=1` body.
     * A SKILL.md at the repository root is not a skill directory: installing it would
     * mix the repository itself into the skills tree.
     */
    fun skillDirectories(treeJson: String, narrowTo: String?): Candidates {
        val tree = try {
            JSONObject(treeJson).optJSONArray("tree")
        } catch (_: Exception) {
            null
        } ?: return Candidates(emptyList(), false)

        val directories = LinkedHashSet<String>()
        for (index in 0 until tree.length()) {
            val item = tree.optJSONObject(index) ?: continue
            if (item.optString("type") != "blob") continue
            val path = item.optString("path")
            if (path.isEmpty()) continue
            val name = path.substringAfterLast('/')
            if (!name.equals("SKILL.md", ignoreCase = true)) continue
            val directory = path.substringBeforeLast('/', "")
            if (directory.isEmpty()) continue
            directories += directory
        }

        val filtered = directories
            .filter { narrowTo == null || it == narrowTo || it.startsWith("$narrowTo/") }
            .sorted()
        return Candidates(filtered.take(MAX_CANDIDATES), truncated = filtered.size > MAX_CANDIDATES)
    }

    fun rawSkillMdUrl(owner: String, repo: String, ref: String, path: String?): String {
        val directory = path?.trim('/').orEmpty()
        val suffix = if (directory.isEmpty()) "SKILL.md" else "$directory/SKILL.md"
        return "https://raw.githubusercontent.com/$owner/$repo/$ref/$suffix"
    }

    fun commitApiUrl(owner: String, repo: String, ref: String): String =
        "https://api.github.com/repos/$owner/$repo/commits/$ref"

    fun treeApiUrl(owner: String, repo: String, ref: String): String =
        "https://api.github.com/repos/$owner/$repo/git/trees/$ref?recursive=1"

    /** Human-facing URL stored as the skill's source so the UI can re-fetch it later. */
    fun htmlSkillUrl(owner: String, repo: String, ref: String, path: String?): String {
        val directory = path?.trim('/').orEmpty()
        return if (directory.isEmpty()) "https://github.com/$owner/$repo/tree/$ref"
        else "https://github.com/$owner/$repo/tree/$ref/$directory"
    }
}
