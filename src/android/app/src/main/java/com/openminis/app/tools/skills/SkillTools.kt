package com.openminis.app.tools.skills

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.tools.ToolExecutionResult
import com.openminis.app.tools.runtime.ToolHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * [T-eta-skill-tools] Model-facing skill tools: list what is installed, read a
 * SKILL.md in full, and read a bounded text resource inside a skill.
 *
 * Ported from Eta `agent/model/AgentSkillToolCatalog.kt` (Mangi-11/Eta @ c15de97);
 * attribution in THIRD_PARTY_LICENSES.md. Reads go through the existing
 * [SkillRepository], so they share its mutation lock and its path guards — the tools
 * add bounds and resolution, not a second way into the skills tree. Installing stays a
 * separate capability; nothing here executes a skill or writes to the tree.
 *
 * Canonical names follow this repository's dotted convention (the `skill.*` prefix is
 * already routed and policy-mapped); the Eta spellings are registered as aliases.
 */
object SkillTools {
    const val LIST = "skill.list"
    const val READ = "skill.read"
    const val RESOURCE = "skill.read_resource"
    const val INSPECT = "skill.inspect_github"
    const val INSTALL = "skill.install_github"

    val aliases: Map<String, List<String>> = mapOf(
        LIST to listOf("skills_list"),
        READ to listOf("skills_read"),
        RESOURCE to listOf("skills_read_resource"),
        INSPECT to listOf("skills_inspect_github"),
        INSTALL to listOf("skills_install_from_github"),
    )

    fun handlers(): List<ToolHandler> = listOf(
        SkillListHandler(),
        SkillReadHandler(),
        SkillReadResourceHandler(),
        SkillInspectGitHubHandler(),
        SkillInstallGitHubHandler(),
    )

    internal fun repository(context: Context): SkillRepository? =
        (context.applicationContext as? MinisApp)?.skillRepository

    private fun SkillRepository.Skill.toEntry(): SkillToolPolicy.SkillEntry =
        SkillToolPolicy.SkillEntry(
            id = id,
            name = name,
            description = description,
            enabled = isEnabled,
        )

    private fun notReady(): ToolExecutionResult = ToolExecutionResult(
        "Error: SKILLS_UNAVAILABLE: the skill repository is not ready yet",
        false,
    )

    internal fun list(argsJson: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val repository = repository(context) ?: return notReady()
        val options = SkillToolPolicy.listOptions(
            queryRaw = args.optString("query").ifBlank { null },
            limitRaw = if (args.has("limit")) args.optInt("limit") else null,
        )
        val entries = repository.skills.value.map { it.toEntry() }
        val matched = entries.filter { SkillToolPolicy.matches(it, options.query) }.sortedBy { it.id }
        return ToolExecutionResult(
            SkillToolPolicy.formatList(matched.take(options.limit), matched.size, options),
            true,
        )
    }

    internal suspend fun read(argsJson: String, context: Context): ToolExecutionResult = withContext(Dispatchers.IO) {
        val args = JSONObject(argsJson)
        val repository = repository(context) ?: return@withContext notReady()
        val request = args.optString("skillId")
        val entries = repository.skills.value.map { it.toEntry() }
        val id = SkillToolPolicy.resolveSkillId(request, entries)
            ?: return@withContext ToolExecutionResult(
                "Error: SKILL_NOT_FOUND: no installed skill matches '$request'. Call ${SkillTools.LIST} to see ids.",
                false,
            )
        val maxChars = SkillToolPolicy.clampMaxChars(if (args.has("maxChars")) args.optInt("maxChars") else null)
        val body = repository.readSkillFile(id, "SKILL.md")
            ?: repository.skills.value.firstOrNull { it.id == id }?.body
            ?: return@withContext ToolExecutionResult(
                "Error: SKILL_UNREADABLE: ${SkillToolPolicy.skillMdPath(id)} could not be read",
                false,
            )
        val truncated = SkillToolPolicy.truncate(body, maxChars)
        repository.recordSkillUse(id)
        val entry = entries.firstOrNull { it.id == id }
        val header = "skill: $id | name: ${entry?.name.orEmpty()} | enabled: ${entry?.enabled ?: true} | " +
            "path: ${SkillToolPolicy.skillMdPath(id)}"
        ToolExecutionResult("$header\n\n${truncated.text}", true)
    }

    internal suspend fun readResource(argsJson: String, context: Context): ToolExecutionResult = withContext(Dispatchers.IO) {
        val args = JSONObject(argsJson)
        val repository = repository(context) ?: return@withContext notReady()
        val request = args.optString("skillId")
        val entries = repository.skills.value.map { it.toEntry() }
        val id = SkillToolPolicy.resolveSkillId(request, entries)
            ?: return@withContext ToolExecutionResult(
                "Error: SKILL_NOT_FOUND: no installed skill matches '$request'. Call ${SkillTools.LIST} to see ids.",
                false,
            )
        val relativePath = when (val resolved = SkillToolPolicy.resourcePath(args.optString("relativePath"))) {
            is SkillToolPolicy.ResourcePath.Refused -> return@withContext ToolExecutionResult(
                "Error: INVALID_PATH: ${resolved.reason}",
                false,
            )
            is SkillToolPolicy.ResourcePath.Ok -> resolved.path
        }
        val maxChars = SkillToolPolicy.clampMaxChars(if (args.has("maxChars")) args.optInt("maxChars") else null)
        val content = repository.readSkillFile(id, relativePath)
            ?: return@withContext ToolExecutionResult(
                "Error: SKILL_RESOURCE_NOT_FOUND: $relativePath in skill $id could not be read as UTF-8 text." +
                    availableFiles(repository, id),
                false,
            )
        val truncated = SkillToolPolicy.truncate(content, maxChars)
        val header = "skill: $id | resource: $relativePath | path: ${SkillToolPolicy.SKILLS_ROOT}/$id/$relativePath"
        ToolExecutionResult("$header\n\n${truncated.text}", true)
    }

    /** Bounded file inventory so a failed resource read tells the model what exists. */
    private fun availableFiles(repository: SkillRepository, id: String): String {
        val files = repository.listSkillFiles(id).take(50)
        if (files.isEmpty()) return ""
        return "\nFiles in this skill: " + files.joinToString(", ")
    }

    internal suspend fun inspectGitHub(argsJson: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val request = args.optString("repository")
        if (request.isBlank()) return ToolExecutionResult("Error: INVALID_ARGS: repository is required", false)
        val rawPath = args.optString("path")
        val path = if (rawPath.isBlank()) {
            null
        } else {
            SkillSourcePolicy.safeRelativeDirectory(rawPath)
                ?: return ToolExecutionResult(
                    "Error: INVALID_PATH: path must be a repository-relative directory without '..' or backslashes",
                    false,
                )
        }
        val store = repository(context) ?: return notReady()
        val inspection = store.inspectGitHub(
            repository = request,
            ref = args.optString("ref").ifBlank { null },
            path = path,
        )
        inspection.error?.let { return ToolExecutionResult("Error: GITHUB_INSPECTION_FAILED: $it", false) }
        if (inspection.paths.isEmpty()) {
            return ToolExecutionResult(
                "No directory in $request contains a SKILL.md, so there is nothing to install.",
                false,
            )
        }
        val header = buildString {
            append("repository: ").append(request).append('\n')
            append("commitSha: ").append(inspection.commitSha).append('\n')
            append(inspection.paths.size).append(" candidate skill director")
                .append(if (inspection.paths.size == 1) "y:" else "ies:")
        }
        val listing = inspection.paths.joinToString("\n") { "- $it" }
        val footer = buildString {
            if (inspection.truncated) {
                append("\n(listing stops at ").append(SkillSourcePolicy.MAX_CANDIDATES)
                    .append(" directories; narrow with path=)")
            }
            append("\nInstall one with ").append(INSTALL)
            append(" using the same repository, the path above and this commitSha as ref, so the content cannot move.")
        }
        return ToolExecutionResult("$header\n$listing\n$footer", true)
    }

    internal suspend fun installGitHub(argsJson: String, context: Context): ToolExecutionResult {
        val args = JSONObject(argsJson)
        val request = args.optString("repository")
        if (request.isBlank()) return ToolExecutionResult("Error: INVALID_ARGS: repository is required", false)
        val rawPath = args.optString("path")
        if (rawPath.isBlank()) {
            return ToolExecutionResult(
                "Error: INVALID_ARGS: path is required — run " + INSPECT +
                    " first and install one of the directories it returned",
                false,
            )
        }
        val store = repository(context) ?: return notReady()
        return when (
            val outcome = store.installFromGitHub(
                repository = request,
                ref = args.optString("ref").ifBlank { null },
                path = rawPath,
            )
        ) {
            is SkillRepository.SkillInstallOutcome.Installed -> {
                val text = buildString {
                    append("installed: ").append(outcome.skill.id)
                    append(" | name: ").append(outcome.skill.name)
                    append("\ncommitSha: ").append(outcome.commitSha)
                    append("\nfiles: ").append(outcome.filesWritten).append(" sibling file(s)")
                    append("\npath: ").append(SkillToolPolicy.skillMdPath(outcome.skill.id))
                    outcome.partialReason?.let { append("\npartial: ").append(it) }
                    append("\nThe skill is available from the next turn.")
                }
                ToolExecutionResult(text, true)
            }
            is SkillRepository.SkillInstallOutcome.Conflict -> ToolExecutionResult(
                "Error: SKILL_CONFLICT: ${outcome.existingId} already exists as a " +
                    (if (outcome.bundled) "built-in skill" else "user skill") +
                    ". This tool never overwrites a skill — update it from Settings → Skills, " +
                    "or install a different directory.",
                false,
            )
            is SkillRepository.SkillInstallOutcome.Failure -> ToolExecutionResult(
                "Error: GITHUB_INSTALL_FAILED: ${outcome.reason}",
                false,
            )
        }
    }
}

class SkillListHandler : ToolHandler {
    override val definition = AgentToolDefinition(
        name = SkillTools.LIST,
        description = "List installed skills with their id, name, description and enabled state. " +
            "Use when the user asks which skills exist, or to find the id to read with ${SkillTools.READ}.",
        parameters = mapOf(
            "query" to AgentToolParam("string", "Optional keyword matched against skill id, name and description"),
            "limit" to AgentToolParam("integer", "Max results, 1-200, default 50"),
        ),
        required = emptyList(),
    )

    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String) =
        SkillTools.list(argsJson, context)
}

class SkillReadHandler : ToolHandler {
    override val definition = AgentToolDefinition(
        name = SkillTools.READ,
        description = "Read the full SKILL.md of an installed skill by id, name or SKILL.md path. " +
            "Use it when a skill looks relevant but its body was not injected into this turn's context.",
        parameters = mapOf(
            "skillId" to AgentToolParam("string", "Installed skill id, name, or SKILL.md path. Use ${SkillTools.LIST} if unsure"),
            "maxChars" to AgentToolParam("integer", "Max characters returned, 512-64000, default 16000"),
        ),
        required = listOf("skillId"),
    )

    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String) =
        SkillTools.read(argsJson, context)
}

class SkillReadResourceHandler : ToolHandler {
    override val definition = AgentToolDefinition(
        name = SkillTools.RESOURCE,
        description = "Read a bounded UTF-8 text resource inside an installed skill, such as references/guide.md. " +
            "The path must be relative to the skill root; this tool never executes scripts and never reads outside the skill.",
        parameters = mapOf(
            "skillId" to AgentToolParam("string", "Installed skill id, name, or SKILL.md path"),
            "relativePath" to AgentToolParam("string", "Safe path relative to the skill root, for example references/guide.md"),
            "maxChars" to AgentToolParam("integer", "Max characters returned, 512-64000, default 16000"),
        ),
        required = listOf("skillId", "relativePath"),
    )

    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String) =
        SkillTools.readResource(argsJson, context)
}


class SkillInspectGitHubHandler : ToolHandler {
    override val definition = AgentToolDefinition(
        name = SkillTools.INSPECT,
        description = "Inspect a public GitHub repository and list every directory that contains a SKILL.md. " +
            "This never installs anything. Use the returned commitSha as ref for installation; " +
            "when several candidates match, ask the user which directory they mean.",
        parameters = mapOf(
            "repository" to AgentToolParam("string", "Public owner/repository or https://github.com/... URL"),
            "ref" to AgentToolParam("string", "Optional branch, tag or commit; omit for the default branch"),
            "path" to AgentToolParam("string", "Optional repository-relative directory used to narrow discovery"),
        ),
        required = listOf("repository"),
    )

    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String) =
        SkillTools.inspectGitHub(argsJson, context)
}

class SkillInstallGitHubHandler : ToolHandler {
    override val definition = AgentToolDefinition(
        name = SkillTools.INSTALL,
        description = "Install one skill directory from a public GitHub repository. Use a path returned by " +
            "${SkillTools.INSPECT} and pass its commitSha as ref, so a moving branch cannot change what is installed. " +
            "An existing skill is never overwritten: a conflict names the id to update from Settings → Skills. " +
            "Bundled scripts are not executed, and the skill becomes available next turn.",
        parameters = mapOf(
            "repository" to AgentToolParam("string", "Public owner/repository or https://github.com/... URL"),
            "path" to AgentToolParam("string", "Exact repository-relative skill directory returned by inspection"),
            "ref" to AgentToolParam("string", "Optional branch, tag or commit — pass the inspection's commitSha to pin it"),
        ),
        required = listOf("repository", "path"),
    )

    override suspend fun execute(argsJson: String, sessionId: String, context: Context, toolId: String) =
        SkillTools.installGitHub(argsJson, context)
}
