package com.openminis.app.ui.navigation

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.background
import com.openminis.app.data.model.ModelSlot
import com.openminis.app.ui.sandbox.FileBrowserViewModel
import com.openminis.app.ui.sandbox.FileItem
import com.openminis.app.ui.settings.SettingsCategory

// T342: Material 3 motion easing curves. Compose-Material3 (1.3.x) ships
// `MotionScheme` only in 1.4-alpha; mirror the spec values directly so we
// don't take a dependency-bump tax just for two CubicBezierEasing instances.
// Source: m3.material.io/styles/motion/easing-and-duration/tokens-specs
internal val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1.0f)

internal val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0.0f, 0.8f, 0.15f)

object Routes {
    const val SESSION_LIST = "sessions"
    /** [T-android-assistant-home] Greeting + 2×2 quick actions; optional start page. */
    const val ASSISTANT_HOME = "assistant_home"
    const val CHAT = "chat/{sessionId}"
    const val SETTINGS = "settings"
    // [T-android-settings-hierarchy] Level 2: the settings of one category (see SettingsCategory).
    const val SETTINGS_CATEGORY = "settings_category/{category}"
    fun settingsCategory(category: String) = "settings_category/$category"
    const val BOTS = "bots"
    const val BOTS_ADD = "bots_add"
    const val BOTS_PROGRESS = "bots_progress"
    const val BOT_DETAILS = "bots/{botId}"
    fun botDetails(botId: String) = "bots/${android.net.Uri.encode(botId)}"
    const val PROVIDER_LIST = "providers"
    const val ADD_PROVIDER = "add_provider"
    const val PROVIDER_DETAIL = "provider/{instanceId}"
    /** [T-android-provider-voice] Read-only shadow Voice Service detail. */
    const val SHADOW_VOICE_DETAIL = "voice_service/{instanceId}"
    const val MODELS = "models"
    const val MODEL_SLOT_DETAIL = "model_slot/{slot}"
    fun modelSlotDetail(slot: ModelSlot) = "model_slot/${slot.name}"
    /** T185: picker that adds model *entries* to the agent-loop set. */
    const val ADD_MODELS_TO_AGENT_LOOP = "add_models_to_agent_loop"
    /** The list of models the agent may use (sub agents, minis-model-use). */
    const val AGENT_LOOP_MODELS = "agent_loop_models"
    const val MODEL_ENTRY_DETAIL = "model_entry/{instanceId}/{entryId}"
    const val ADD_CUSTOM_MODEL = "add_custom_model/{instanceId}"
    const val STORAGE = "storage"
    const val BACKUP = "backup"
    const val BACKUP_DESTINATIONS = "backup_destinations"
    const val BACKUP_HISTORY_DETAIL = "backup_history_detail"
    const val BACKUP_DESTINATION_BROWSE = "backup_destination_browse"
    const val RESTORE_BROWSE = "restore_browse"
    const val RESTORE_SERVERS = "restore_servers"
    const val SESSION_STORAGE_DETAIL = "session_storage/{sessionId}"
    const val ROOTFS_MANAGEMENT = "rootfs_management"
    const val MIRROR_CATEGORY_DETAIL = "mirror_category/{categoryKey}"
    fun mirrorCategoryDetail(categoryKey: String) = "mirror_category/$categoryKey"
    const val FILE_BROWSER = "file_browser"
    const val FILE_PREVIEW = "file_preview"
    const val ENV_VARS = "env_vars"
   const val SKILLS = "skills"

    /** [T-eta-character-cards] Imported character cards. */
    const val CHARACTERS = "characters"

    /** Sub agent roster and the delegation switch. */
    const val SUB_AGENTS = "sub_agents"

    /** [T-eta-character-cards] One stored character: its card and its world book. */
    const val CHARACTER_DETAIL = "character/{characterId}"
    const val SKILL_DETAIL = "skill/{skillId}"
    const val SKILL_FILE = "skill_file/{skillId}/{relativePath}"
    const val MINIS_SKILLS_BROWSER = "minis_skills_browser"

    fun skillDetail(skillId: String) = "skill/$skillId"

    /** [T-eta-character-cards] One stored character: its card and its world book. */
    fun characterDetail(characterId: String) = "character/$characterId"
    fun skillFile(skillId: String, relativePath: String = "SKILL.md"): String {
        // Path may contain `/`, which the nav library treats as a route
        // separator. URL-encode so subdirectory paths survive a round-trip.
        val encoded = java.net.URLEncoder.encode(relativePath, "UTF-8").replace("+", "%20")
        return "skill_file/$skillId/$encoded"
    }
    const val TERMINAL = "terminal?initCommand={initCommand}&sessionId={sessionId}"
    fun terminal(initCommand: String? = null, sessionId: String? = null): String {
        // URLEncoder follows application/x-www-form-urlencoded — spaces become `+`.
        // Nav library only %-decodes the route, so `+` would reach the screen literally.
        // Replace `+` with `%20` so Nav decodes it back to a space.
        fun enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20")
        val params = buildList {
            if (initCommand != null) add("initCommand=${enc(initCommand)}")
            if (sessionId != null) add("sessionId=${enc(sessionId)}")
        }
        return if (params.isEmpty()) "terminal" else "terminal?${params.joinToString("&")}"
    }
    /** Chat-files browser: opens FileBrowser rooted at /var/minis for the session. */
    const val CHAT_FILES = "chat_files/{sessionId}"
    fun chatFiles(sessionId: String) = "chat_files/$sessionId"
    const val MEMORY = "memory"
    /** [T-mcp-integration-android] MCP Integrations management screen. */
    const val MCP = "mcp"
    /** [T-soul-md] SOUL.md editor. */
    const val SOUL = "soul"
    /** [T-system-prompt-modules] Editor for the built-in system prompt modules. */
    const val SYSTEM_PROMPT = "system_prompt"
    /** [T-system-prompt-modules] Advanced editor for the shipped prompt sections. */
    const val SYSTEM_PROMPT_MODULES = "system_prompt_modules"
    const val MEMORY_FILE_EDIT = "memory_file/{fileName}/{isGlobal}"
    const val PERMISSIONS = "permissions"
    /** Root status, what Root is used for, and the android-root-cli surface. */
    const val ROOT = "root"
    const val VIRTUAL_SCREEN_SETTINGS = "virtual_screen_settings"
    /** T323: System Permissions (Accessibility service status, etc.). */
    const val SYSTEM_PERMISSIONS = "system_permissions"
    /** [T-eta-xposed-groups] The switches the LSPosed module reads. */
    /** [T-system-enhance-android] Root + module status, and what each of them unlocks. */
    const val SYSTEM_ENHANCE = "system_enhance"
    const val USAGE_STATS = "usage_stats"
    const val LOGS = "logs"
    const val LOG_DETAIL = "log_detail/{fileName}"
    const val APPEARANCE = "appearance"
    const val BACKGROUND = "background"
    const val ABOUT = "about"
    const val ONBOARDING_MODELS = "onboarding_models"
    /** T219-2: Mount external folders settings + detail. */
    const val MOUNTED_FOLDERS = "mounted_folders"
    const val MOUNTED_FOLDERS_DETAIL = "mounted_folders_detail/{mountId}"
    fun mountedFoldersDetail(mountId: String) = "mounted_folders_detail/$mountId"
    /** T235: Shared folders (Shared / Skills / Memory) — fixed list. */
    const val SHARED_FOLDERS = "shared_folders"
    const val SHARED_FOLDERS_DETAIL = "shared_folders_detail/{folderId}"
    fun sharedFoldersDetail(folderId: String) = "shared_folders_detail/$folderId"
    /** [T-android-scheduled-tasks-design] Scheduled tasks list + editor. */
    const val SCHEDULED_TASKS = "scheduled_tasks"
    const val SCHEDULED_TASK_EDIT = "scheduled_tasks/edit?taskId={taskId}&botId={botId}"
    fun scheduledTaskEdit(taskId: String? = null, botId: String? = null): String = buildString {
        append("scheduled_tasks/edit")
        val args = listOfNotNull(taskId?.let { "taskId=$it" }, botId?.let { "botId=$it" })
        if (args.isNotEmpty()) append("?").append(args.joinToString("&"))
    }
    // [T-android-scheduled-tasks-run-records] per-task execution log.
    const val SCHEDULED_TASK_RUNS = "scheduled_tasks/runs/{taskId}"
    fun scheduledTaskRuns(taskId: String): String = "scheduled_tasks/runs/$taskId"

    fun logDetail(fileName: String) = "log_detail/$fileName"
    fun sessionStorageDetail(sessionId: String) = "session_storage/$sessionId"
    fun memoryFileEdit(fileName: String, isGlobal: Boolean) = "memory_file/$fileName/$isGlobal"
    fun chat(sessionId: String) = "chat/$sessionId"
    fun providerDetail(instanceId: String) = "provider/$instanceId"
    fun shadowVoiceDetail(instanceId: String) = "voice_service/$instanceId"
    fun addModelsToGroup(groupId: String) = "add_models_to_group/$groupId"
    // [T-android-model-entry-route-slash-crash] entryId is a composite key
    // "<instanceId>/<modelId>" (compositeEntryKey) — it CONTAINS a '/'. Left
    // raw, that slash splits the route into an extra path segment, so the
    // built route no longer matches the registered MODEL_ENTRY_DETAIL pattern
    // (model_entry/{instanceId}/{entryId}) and navigate() throws
    // IllegalArgumentException "destination … cannot be found" — a guaranteed
    // crash on tapping any model whose id carries a '/'. URL-encode it so the
    // slash becomes %2F (one segment); the receiver decodes it back.
    fun modelEntryDetail(instanceId: String, entryId: String) =
        "model_entry/${android.net.Uri.encode(instanceId)}/${android.net.Uri.encode(entryId)}"
    fun addCustomModel(instanceId: String) = "add_custom_model/$instanceId"
}

/** Holder for file preview navigation state (not serializable via nav args). */
internal object FilePreviewHolder {
    var currentItem: FileItem? = null
    var fileBrowserViewModel: FileBrowserViewModel? = null
}
