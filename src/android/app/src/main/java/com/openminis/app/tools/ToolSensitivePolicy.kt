package com.openminis.app.tools

import com.openminis.app.data.model.AgentContentPart
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * [T-android-sensitive-tool-redaction] Tools whose raw arguments and results
 * must not enter the persisted transcript.
 *
 * Ported from Eta `agent/model/AgentSensitiveToolPolicy.kt` and the transcript
 * redaction in `agent/model/AgentConversationCodec.kt` (Mangi-11/Eta @
 * c15de97); attribution is centralised in PROVENANCE.md.
 *
 * Eta classifies a tool call once, then replaces the matching argument and
 * result payloads with a fixed placeholder while the live turn keeps the real
 * data. The port keeps that split: the model still sees what it asked for
 * during the turn, and what lands in Room (and in the run recovery transcript)
 * is the placeholder only. Every `mcp_` prefixed tool is sensitive, because a
 * third-party server can expose anything.
 */
object ToolSensitivePolicy {

    /** Placeholder stored in place of a sensitive tool's argument object. */
    const val ARGUMENTS_PLACEHOLDER = "[redacted: sensitive tool arguments are not persisted]"

    /** Placeholder stored in place of a sensitive tool's result. */
    const val RESULT_PLACEHOLDER = "[redacted: sensitive tool result is not persisted]"

    /** Placeholder for a whole part that could not be inspected. */
    const val UNINSPECTABLE_PLACEHOLDER = "[redacted: sensitive tool activity is not persisted]"

    fun isSensitive(toolName: String): Boolean {
        val name = toolName.trim()
        if (name.isEmpty()) return false
        val normalized = name.lowercase(Locale.ROOT)
        if (
            normalized.startsWith("mcp_") ||
            normalized.startsWith("mcp.") ||
            normalized.startsWith("mcp__")
        ) {
            return true
        }
        return sensitiveKeys.contains(sensitiveKey(normalized))
    }

    /**
     * The key the table is compared on: case and `.`/`_`/`-` carry no meaning in
     * a tool name, and the registry resolves those spellings to the same tool
     * ([com.openminis.app.tools.runtime.ToolRegistry.canonicalName] strips exactly
     * these characters). Comparing on the same key is what makes a call that
     * reaches a listed tool under another spelling — `android_clipboard` for
     * `android.clipboard` — land in the redaction instead of beside it.
     */
    private fun sensitiveKey(name: String): String = name.filter { it.isLetterOrDigit() }

    /** True when any call in [parts] is sensitive, so the payload must be inspected. */
    fun containsSensitiveToolUse(parts: List<AgentContentPart>): Boolean =
        parts.any { it is AgentContentPart.ToolUse && isSensitive(it.name) }

    /**
     * Redacts a serialized transcript payload (the shape ChatViewModel writes:
     * an array of `{"type": ..., "value": ...}` entries).
     *
     * The fast path returns the input untouched when no sensitive tool call is
     * present. When one is, the payload must be inspected; a payload that cannot
     * be parsed is replaced as a whole, because the alternative is persisting
     * data that was never inspected.
     */
    fun redactTranscriptJson(parts: List<AgentContentPart>, partsJson: String): String {
        if (!containsSensitiveToolUse(parts)) return partsJson
        val array = runCatching { JSONArray(partsJson) }.getOrElse { return uninspectablePayload() }
        var changed = false
        for (index in 0 until array.length()) {
            val entry = array.optJSONObject(index) ?: continue
            val value = entry.optJSONObject("value") ?: continue
            if (!isSensitive(value.optString("name"))) continue
            when (entry.optString("type")) {
                "toolUse" -> {
                    value.put("input", ARGUMENTS_PLACEHOLDER)
                    value.put("description", "")
                    value.put("pageURL", "")
                    value.put("imageFilePath", "")
                    changed = true
                }
                "toolResult" -> {
                    value.put("output", RESULT_PLACEHOLDER)
                    value.put("snapshot", JSONObject().put("type", "text").put("text", RESULT_PLACEHOLDER))
                    changed = true
                }
                else -> Unit
            }
        }
        return if (changed) array.toString() else partsJson
    }

    /**
     * The tool-result part as it may be persisted: a sensitive result keeps its
     * identity, error flag and pairing but loses text and image bytes.
     */
    fun redactResultPart(part: AgentContentPart.ToolResult): AgentContentPart.ToolResult =
        if (!isSensitive(part.name)) {
            part
        } else {
            part.copy(
                content = RESULT_PLACEHOLDER,
                imageData = null,
                imageMimeType = null,
                imageLinuxPath = null,
            )
        }

    private fun uninspectablePayload(): String = JSONArray()
        .put(
            JSONObject()
                .put("type", "text")
                .put("value", UNINSPECTABLE_PLACEHOLDER),
        )
        .toString()

    /**
     * Eta's list, kept verbatim, plus every Minis tool that carries the same
     * classes of data under this build's own catalog names — and the aliases the
     * registry registers for them, because a call may arrive under either
     * spelling. Unknown third-party tools are covered by the MCP prefix rule
     * above.
     *
     * The port's first revision listed the Minis half as `android_clipboard`,
     * `android_settings`, `android_sms`, … — names this build registers nowhere,
     * so none of those device tools was actually redacted. The names below are
     * the ones `ToolRegistry` holds (canonical `android.*`/`linux.*` form plus
     * the legacy alias registered next to it).
     *
     * Included: the raw payload carries data about the user or their device —
     * messages, calls, contacts, calendar entries, location (including the
     * coordinates `android.weather` resolves and echoes when it is asked for the
     * device's own position), clipboard content, Android settings, the device
     * capability/environment report, logcat and crash/ANR dumps, app usage, the
     * media library, memory, and read images.
     *
     * Deliberately excluded, matching Eta's own boundary: the screen tree and
     * package inventory (`android_ui`, `android_app`), browsing
     * (`browser_use`), the workspace file and shell tools (`file_read`,
     * `file_write`, `file_edit`, `shell_execute`, `linux.*`, `root.shell`),
     * generic orchestration (`subagent`, `system.jobs`, goals, todos) and
     * device-state queries that carry no user content (`android.wifi.info`,
     * `android.wifi.scan`, `android.bluetooth.*`, `android.media.info`,
     * `android.media.control`, `android.intent.send`, `android.web.*`). Their
     * payloads are model-authored, public, or files the user already owns.
     */
    private val SENSITIVE_TOOLS = setOf(
        // Eta's list, verbatim (Mangi-11/Eta @ c15de97). `search_contacts` and
        // `read_image` double as this build's aliases for android.contacts.search
        // and linux.file.image.read.
        "get_setting",
        "set_setting",
        "wifi_credentials",
        "recent_notifications",
        "search_notification_history",
        "recent_app_activity",
        "app_usage_summary",
        "get_current_location",
        "get_device_environment",
        "list_alarms",
        "list_active_timers",
        // [T-eta-xposed-groups] The same two tools under this catalog's names: what the user set
        // aside for later is personal context, so the transcript keeps a placeholder.
        "android.alarm.list",
        "android.alarm.timers",
        // [T-eta-xposed-groups] Clipboard history and the health summary carry the user's own
        // clipboard content and body measurements.
        "android.clipboard.history",
        "android.health.summary",
        "search_clipboard_history",
        "get_health_summary",
        "read_sms_code",
        "get_logcat",
        "search_media",
        "search_audio",
        "search_recordings",
        "search_files",
        "search_calendar_events",
        "search_contacts",
        "search_call_history",
        "search_messages",
        "search_downloads",
        "search_coloros_notes",
        "search_coloros_recordings",
        "search_recording_summaries",
        "search_coloros_memories",
        "search_saved_places",
        "search_personal_orders",
        "search_qq_chat_images",
        "search_wechat_chat_images",
        "read_image",
        "memory_get",
        "memory_write",
        "character_memory_get",
        "character_memory_write",

        // Minis catalog — device settings (Eta get_setting / set_setting).
        "android.settings.get",
        "system.get_setting",
        "android.settings.set",
        "system.set_setting",

        // Minis catalog — device environment (Eta get_device_environment).
        "android.capabilities",
        "android_capabilities",

        // Minis catalog — messages, calls, contacts and calendar entries
        // (Eta read_sms_code, search_messages, search_call_history,
        // search_contacts, search_calendar_events; the write side carries the
        // same person-linked data in its arguments).
        "android.sms.read",
        "read_sms",
        // [T-eta-xposed-groups] Eta's read_sms_code under this catalog's name: the payload is a
        // one-time code, which is exactly the kind of value that must not sit in the transcript.
        "android.sms.code",
        "read_sms_code",
        "android.call_log.read",
        "read_call_log",
        "android.contacts.search",
        "android.contacts.manage",
        "manage_contacts",
        "android.calendar.read",
        "read_calendar",
        "android.calendar.create",
        "create_calendar_event",
        "android.calendar.update",
        "update_calendar_event",
        "android.calendar.delete",
        "delete_calendar_event",

        // Minis catalog — location (Eta get_current_location). android.weather
        // belongs here: asked for the device's own position it resolves the
        // coordinates and reports them back in the forecast.
        "android.location.get",
        "get_location",
        "android.weather",
        "get_weather",

        // Minis catalog — clipboard (Eta search_clipboard_history).
        "android.clipboard",
        "clipboard",

        // Minis catalog — logcat and crash/ANR diagnostics (Eta get_logcat).
        "android.logs",
        "android_logs",
        "android.diagnose",
        "android_diagnose",

        // Minis catalog — activity and library (Eta app_usage_summary,
        // recent_app_activity, search_media).
        "android.app.usage",
        "app_usage",
        "android.media.images",
        "list_media_images",

        // [T-eta-xposed-groups] Minis catalog — ColorOS system memory (Eta
        // search_coloros_memories, search_saved_places, search_personal_orders; those
        // three spellings are already in the Eta list above). Memories and orders carry
        // bills, pickup codes and addresses, so the transcript keeps a placeholder.
        "android.coloros.memory",
        "android.coloros.orders",
        "android.coloros.places",
        "android.coloros.notes",
        "android.coloros.recordings",
        "android.coloros.recording_summaries",

        // [T-eta-notification-history] Minis catalog — notification shade and history
        // (Eta recent_notifications, search_notification_history). Notification bodies
        // routinely carry one-time codes and private messages, so the transcript keeps
        // only a placeholder.
        "notification.recent",
        "recent_notifications",
        "notification.search",
        "search_notification_history",

        // [T-eta-character-cards] Story memory is private fiction; the transcript keeps a
        // placeholder while the live turn sees the real text.
        "roleplay.memory_read",
        "roleplay.memory_write",
        "character_memory_get",
        "character_memory_write",

        // Minis catalog — canonical twin of read_image above.
        "linux.file.image.read",
    )

    /**
     * [SENSITIVE_TOOLS] keyed once, after the table it derives from: an object
     * property reads the fields declared above it, so this has to stay last.
     */
    private val sensitiveKeys: Set<String> = SENSITIVE_TOOLS.mapTo(HashSet()) { sensitiveKey(it) }
}
