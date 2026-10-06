package com.openminis.app.backup

import com.openminis.app.prompt.PromptModuleRegistry
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.scheduled.ScheduledTaskPermissionTier
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.json.JSONObject

/**
 * The user's own settings, which a reinstall (or a move to a differently signed build) would otherwise lose:
 * interface and behaviour preferences, the custom system prompt with its module overrides, and scheduled
 * tasks. They travel in `data/app_settings.json` inside the PROVIDERS category, the way custom thinking
 * rules and sub agents do, so the set of backup categories does not change.
 *
 * What may be restored is decided here, and only here, and fails closed: a package is untrusted input.
 *  - Preferences: an explicit allowlist of file and key. Permission grants, approvals, device identity,
 *    install state, secrets and caches are never in it, so a crafted package cannot switch any of them on.
 *  - System prompt: only the custom prompt and known module ids; text is size-capped.
 *  - Scheduled tasks: restored, but a task with full access comes back switched off, because granting
 *    full access needs the user's confirmation in the editor and a package must not be a way around it.
 *
 * Everything in this object is pure (no Context), so the rules are unit-tested directly.
 */
internal object BackupAppSettings {

    const val FILE_NAME = "app_settings.json"
    const val VERSION = 1

    /** Longest custom prompt or module override text accepted either way. */
    const val MAX_PROMPT_CHARS = 256 * 1024
    private const val MAX_PREF_STRING = 16 * 1024
    private const val MAX_KEY_LENGTH = 128
    const val MAX_TASKS = 200

    /**
     * Preference file -> the keys that may travel, each with the type its consumer reads it as
     * (`b` Boolean, `i` Int, `l` Long, `f` Float, `s` String). SharedPreferences throws
     * ClassCastException when a key holds another type than the reader asks for, and some readers run
     * in `Application.onCreate`, so a package must never decide the type that lands on disk.
     * Anything not listed here is neither exported nor restored.
     */
    val ALLOWED_PREFS: Map<String, Map<String, Char>> = mapOf(
        "appearance_prefs" to mapOf(
            "theme_mode" to 'i', "accent_color" to 'i', "ui_style" to 'i', "launch_session" to 'i',
            "returnKeyBehavior" to 'i', "keepScreenAwakeDuringTasks" to 'b', "tool_preview" to 'b',
            "tool_status_bar" to 'b', "chat.autoFocusAfterReply" to 'b', "appearance.show_chat_title" to 'b',
            "chat.autoExpandThinking" to 'b', "autoGroupingEnabled" to 'b', "font_chat_input" to 'i',
            "font_message" to 'i', "font_app_base" to 'i', "app_language" to 's',
        ),
        "ui_prefs" to mapOf("tablet_list_pane_width_dp" to 'f', "tablet_list_pane_collapsed" to 'b'),
        "composer_input_prefs" to mapOf("composer_input_mode_pref" to 's'),
        "minis_fast_mode_prefs" to mapOf("codexFastModeEnabled" to 'b'),
        "minis_auto_compact_prefs" to mapOf("autoCompactOnThreshold" to 'b'),
        "minis_steps_presentation_prefs" to mapOf("stepsPresentation" to 's'),
        "minis_memory_prefs" to mapOf("memory.global.enabled" to 'b'),
        "minis_agent_presets" to mapOf("default_for_new_sessions" to 's'),
        "browser_prefs" to mapOf(
            "idle_timeout_minutes" to 'i', "browser_custom_viewport_width" to 'i',
            "browser_custom_viewport_height" to 'i',
        ),
        "file_browser_prefs" to mapOf("file_browser_show_hidden" to 'b'),
        "voice_prefs" to mapOf(
            "readReplies" to 'b', "readReplies.muted" to 'b', "readReplies.speed" to 'f',
            "readReplies.systemVoice" to 's', "readReplies.systemVoiceLabel" to 's',
        ),
        "speech_recognition" to mapOf("locale" to 's'),
    )

    /** Preference files whose keys are a family: `enabled.<module id>` and `mirror.selected.<category>`. */
    private val ALLOWED_PREFIXES: Map<String, Map<String, Char>> = mapOf(
        "minis_system_prompt" to mapOf("enabled." to 'b'),
        "mirror_settings" to mapOf("mirror.selected." to 's', "mirror.useCustom." to 'b'),
    )

    /** The type [key] must have in [prefsName], or null when the key may not travel at all. */
    fun expectedType(prefsName: String, key: String): Char? {
        if (key.isEmpty() || key.length > MAX_KEY_LENGTH) return null
        ALLOWED_PREFS[prefsName]?.get(key)?.let { return it }
        val (prefix, type) = ALLOWED_PREFIXES[prefsName]?.entries
            ?.firstOrNull { key.startsWith(it.key) && key.length > it.key.length } ?: return null
        // A prompt-module flag only means something for a module that exists.
        if (prefsName == "minis_system_prompt" && PromptModuleRegistry.byId(key.removePrefix(prefix)) == null) return null
        return type
    }

    private fun typeOf(value: Any): Char? = when (value) {
        is Boolean -> 'b'
        is Int -> 'i'
        is Long -> 'l'
        is Float -> if (value.isFinite()) 'f' else null
        is String -> 's'
        else -> null
    }

    fun isAllowed(prefsName: String, key: String): Boolean = expectedType(prefsName, key) != null

    /** The preference files that carry settings; the exporter reads exactly these. */
    val prefsFileNames: Set<String> get() = ALLOWED_PREFS.keys + ALLOWED_PREFIXES.keys

    // -- Preferences -----------------------------------------------------------------------------

    /** `{ "t": "b|i|l|f|s", "v": ... }`, or null for a value this format does not carry. */
    fun encodeValue(value: Any?): JsonObject? = when (value) {
        is Boolean -> buildJsonObject { put("t", "b"); put("v", value) }
        is Int -> buildJsonObject { put("t", "i"); put("v", value) }
        is Long -> buildJsonObject { put("t", "l"); put("v", value) }
        is Float -> buildJsonObject { put("t", "f"); put("v", value.toDouble()) }
        is String -> if (value.length <= MAX_PREF_STRING) buildJsonObject { put("t", "s"); put("v", value) } else null
        else -> null
    }

    private fun decodeValue(o: JsonObject): Any? {
        val v = o["v"] as? JsonPrimitive ?: return null
        return when ((o["t"] as? JsonPrimitive)?.contentOrNull) {
            "b" -> v.booleanOrNull
            "i" -> v.longOrNull?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
            "l" -> v.longOrNull
            "f" -> v.doubleOrNull?.toFloat()
            "s" -> v.contentOrNull?.takeIf { v.isString && it.length <= MAX_PREF_STRING }
            else -> null
        }
    }

    /** The allowed entries of each preference file, as the exporter reads them from SharedPreferences. */
    fun snapshotPrefs(read: (prefsName: String) -> Map<String, *>): JsonObject = buildJsonObject {
        for (name in prefsFileNames.sorted()) {
            val values = read(name)
            val encoded = buildJsonObject {
                for ((key, value) in values.entries.sortedBy { it.key }) {
                    // A value of the wrong type is a leftover, not a setting: leave it out.
                    if (value == null || expectedType(name, key) != typeOf(value)) continue
                    encodeValue(value)?.let { put(key, it) }
                }
            }
            if (encoded.isNotEmpty()) put(name, encoded)
        }
    }

    /** What a package's preferences amount to once the allowlist has been applied. */
    class PrefsPlan(val writes: Map<String, Map<String, Any>>, val rejected: Int) {
        val count: Int get() = writes.values.sumOf { it.size }
    }

    fun planPrefs(prefs: JsonObject?): PrefsPlan {
        if (prefs == null) return PrefsPlan(emptyMap(), 0)
        val writes = linkedMapOf<String, Map<String, Any>>()
        var rejected = 0
        for ((name, body) in prefs) {
            val entries = body as? JsonObject
            if (entries == null) { rejected++; continue }
            val accepted = linkedMapOf<String, Any>()
            for ((key, raw) in entries) {
                val value = (raw as? JsonObject)?.let(::decodeValue)
                // The package's own type tag only says how to parse the value; the key decides
                // what may be stored.
                val expected = expectedType(name, key)
                if (value == null || expected == null || typeOf(value) != expected) rejected++ else accepted[key] = value
            }
            if (accepted.isNotEmpty()) writes[name] = accepted
        }
        return PrefsPlan(writes, rejected)
    }

    // -- System prompt ---------------------------------------------------------------------------

    class PromptPlan(val custom: String?, val modules: Map<String, String>, val rejected: Int) {
        val count: Int get() = (if (custom != null) 1 else 0) + modules.size
    }

    fun planPrompt(prompt: JsonObject?): PromptPlan {
        if (prompt == null) return PromptPlan(null, emptyMap(), 0)
        var rejected = 0
        var custom: String? = null
        (prompt["custom"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let {
            if (it.length <= MAX_PROMPT_CHARS) custom = it else rejected++
        }
        val modules = linkedMapOf<String, String>()
        for ((id, raw) in (prompt["modules"] as? JsonObject).orEmpty()) {
            val text = (raw as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (text == null || text.length > MAX_PROMPT_CHARS || PromptModuleRegistry.byId(id) == null) rejected++
            else modules[id] = text
        }
        return PromptPlan(custom, modules, rejected)
    }

    // -- Scheduled tasks -------------------------------------------------------------------------

    class TasksPlan(
        val toWrite: List<ScheduledTask>,
        /** Already present locally under the same id: kept as they are. */
        val skipped: Int,
        val rejected: Int,
        /** Restored with full access, therefore switched off. */
        val disabledForFullAccess: Int,
    )

    fun planTasks(rows: JsonArray?, localIds: Set<String>): TasksPlan {
        if (rows == null) return TasksPlan(emptyList(), 0, 0, 0)
        val write = mutableListOf<ScheduledTask>()
        var skipped = 0
        var rejected = 0
        var disabled = 0
        val seen = HashSet<String>()
        for ((index, row) in rows.withIndex()) {
            if (index >= MAX_TASKS) { rejected += rows.size - MAX_TASKS; break }
            val task = runCatching { ScheduledTask.fromJson(JSONObject(row.toString())) }.getOrNull()
            if (task == null || task.id.isBlank() || !seen.add(task.id)) { rejected++; continue }
            if (task.id in localIds) { skipped++; continue }
            if (task.permissionTier == ScheduledTaskPermissionTier.FULL) {
                disabled++
                write += task.copy(enabled = false)
            } else {
                write += task
            }
        }
        return TasksPlan(write, skipped, rejected, disabled)
    }

    fun tasksToJson(tasks: List<ScheduledTask>): JsonArray =
        JsonArray(tasks.take(MAX_TASKS).map { BackupFormat.json.parseToJsonElement(it.toJson().toString()) })

    // -- Document --------------------------------------------------------------------------------

    fun document(prefs: JsonObject, customPrompt: String?, moduleOverrides: Map<String, String>, tasks: JsonArray): JsonObject =
        buildJsonObject {
            put("version", VERSION)
            put("prefs", prefs)
            put(
                "system_prompt",
                buildJsonObject {
                    customPrompt?.let { put("custom", it) }
                    put("modules", buildJsonObject { for ((id, text) in moduleOverrides.toSortedMap()) put(id, text) })
                },
            )
            put("scheduled_tasks", tasks)
        }

    fun isEmpty(doc: JsonObject): Boolean =
        (doc["prefs"] as? JsonObject).orEmpty().isEmpty() &&
            ((doc["system_prompt"] as? JsonObject)?.let { p ->
                p["custom"] == null && (p["modules"] as? JsonObject).orEmpty().isEmpty()
            } ?: true) &&
            (doc["scheduled_tasks"] as? JsonArray).orEmpty().isEmpty()

}
