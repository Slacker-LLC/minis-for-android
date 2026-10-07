package com.openminis.app.debug

import com.openminis.app.debug.DebugMethodRegistry.MethodSpec
import com.openminis.app.debug.DebugMethodRegistry.ParamSpec
import com.openminis.app.debug.DebugMethodRegistry.ex
import com.openminis.app.BuildConfig
import org.json.JSONArray
import org.json.JSONObject

/** Part 3 of the method catalogue [DebugMethodRegistry.methods] is built from. */
internal val baseMethodsPart3: List<MethodSpec> = listOf(
        MethodSpec(
            name = "memory.files.delete",
            description = "Delete one memory file. Cannot delete files the store protects.",
            params = listOf(
                ParamSpec("name", "string", required = true, description = "File name (no '/' or '..')."),
            ),
            returns = "{ok:true}",
            example = ex("name" to "notes.md"),
        ),
        MethodSpec(
            name = "memory.globalToggle",
            description = "Return whether global memory is enabled.",
            params = emptyList(),
            returns = "{enabled:boolean}",
            example = ex(),
        ),
        MethodSpec(
            name = "memory.setGlobalEnabled",
            description = "Enable or disable global memory for all sessions.",
            params = listOf(
                ParamSpec("enabled", "bool", required = true, description = "New enabled state."),
            ),
            returns = "{ok:true, enabled}",
            example = ex("enabled" to true),
        ),
        MethodSpec(
            name = "soul.get",
            description = "Read SOUL.md: persona name, style, language, and body.",
            params = emptyList(),
            returns = "{name, style, lang, body}",
            example = ex(),
        ),
        MethodSpec(
            name = "soul.save",
            description = "Save SOUL.md. Omitted fields keep their current values.",
            params = listOf(
                ParamSpec("name", "string", required = false, description = "Persona name."),
                ParamSpec("style", "string", required = false, description = "Persona style."),
                ParamSpec("lang", "string", required = false, description = "Persona language."),
                ParamSpec("body", "string", required = false, description = "SOUL.md body."),
            ),
            returns = "{ok:true}",
            example = ex("name" to "Pi", "lang" to "zh", "body" to "你是…"),
        ),

        // ── MCP (web remote: mcp.*) ───────────────────────────────────────────
        MethodSpec(
            name = "mcp.list",
            description = "List configured MCP servers.",
            params = emptyList(),
            returns = "{servers:[{id, note, enabled, url, command, args, env, headers, startupTimeoutSeconds, createdAt}]}",
            example = ex(),
        ),
        MethodSpec(
            name = "mcp.get",
            description = "Return one MCP server configuration.",
            params = listOf(ParamSpec("serverId", "string", required = true, description = "MCP server identifier.")),
            returns = "{server:{id,note,enabled,url,command,args,env,headers,startupTimeoutSeconds,createdAt}}",
            example = ex("serverId" to "github"),
        ),
        MethodSpec(
            name = "mcp.create",
            description = "Create an HTTP/SSE or STDIO MCP server. Exactly one of url/command is required.",
            params = listOf(
                ParamSpec("serverId", "string", required = true, description = "Unique server identifier."),
                ParamSpec("url", "string|null", required = false, description = "HTTP/SSE endpoint."),
                ParamSpec("command", "string|null", required = false, description = "STDIO executable."),
                ParamSpec("args", "[string]", required = false, description = "STDIO arguments."),
                ParamSpec("env", "object", required = false, description = "STDIO environment map."),
                ParamSpec("headers", "object", required = false, description = "HTTP header map."),
                ParamSpec("note", "string", required = false, description = "Human-readable note."),
                ParamSpec("enabled", "bool", required = false, default = true, description = "Global enabled state."),
                ParamSpec("startupTimeoutSeconds", "int|null", required = false, description = "STDIO initialize timeout."),
            ),
            returns = "{server:{…}}",
            example = ex("serverId" to "docs", "url" to "https://example.test/mcp"),
        ),
        MethodSpec(
            name = "mcp.update",
            description = "Patch an existing MCP server; omitted fields keep their current values.",
            params = listOf(
                ParamSpec("serverId", "string", required = true, description = "MCP server identifier."),
                ParamSpec("url", "string|null", required = false, description = "HTTP/SSE endpoint."),
                ParamSpec("command", "string|null", required = false, description = "STDIO executable."),
                ParamSpec("args", "[string]", required = false, description = "STDIO arguments."),
                ParamSpec("env", "object", required = false, description = "STDIO environment map."),
                ParamSpec("headers", "object", required = false, description = "HTTP header map."),
                ParamSpec("note", "string|null", required = false, description = "Human-readable note."),
                ParamSpec("enabled", "bool", required = false, description = "Global enabled state."),
                ParamSpec("startupTimeoutSeconds", "int|null", required = false, description = "STDIO initialize timeout."),
            ),
            returns = "{server:{…}}",
            example = ex("serverId" to "docs", "enabled" to false),
        ),
        MethodSpec(
            name = "mcp.import",
            description = "Import MCP JSON accepted by the native repository.",
            params = listOf(ParamSpec("configJson", "string", required = true, description = "Claude Desktop-compatible or bare MCP JSON.")),
            returns = "{count,servers:[{…}]}",
            example = ex("configJson" to "{\"mcpServers\":{\"docs\":{\"url\":\"https://example.test/mcp\"}}}"),
        ),
        MethodSpec(
            name = "mcp.importUrl",
            description = "Download MCP JSON from a public HTTPS URL and import it into the native repository.",
            params = listOf(ParamSpec("url", "string", required = true, description = "Public HTTPS URL containing MCP JSON.")),
            returns = "{sourceURL,count,servers:[{…}]}",
            example = ex("url" to "https://raw.githubusercontent.com/example/project/main/mcp.json"),
        ),
        MethodSpec(
            name = "mcp.toggle",
            description = "Enable or disable an MCP server.",
            params = listOf(
                ParamSpec("serverId", "string", required = true, description = "MCP server identifier."),
                ParamSpec("enabled", "bool", required = true, description = "New enabled state."),
            ),
            returns = "{ok:true}",
            example = ex("serverId" to "mcp-1", "enabled" to true),
        ),
        MethodSpec(
            name = "mcp.delete",
            description = "Permanently remove an MCP server.",
            params = listOf(
                ParamSpec("serverId", "string", required = true, description = "MCP server identifier."),
            ),
            returns = "{ok:true}",
            example = ex("serverId" to "mcp-1"),
        ),

        // ── Environment + storage (web remote) ───────────────────────────────
        MethodSpec(
            name = "environments.list",
            description = "List environment-variable metadata. Secret values are never returned.",
            params = emptyList(),
            returns = "{entries:[{id,key,note,createdAt,hasValue}]}",
            example = ex(),
        ),
        MethodSpec(
            name = "environments.create",
            description = "Create an encrypted environment variable; value is write-only.",
            params = listOf(
                ParamSpec("key", "string", required = true, description = "Shell environment key."),
                ParamSpec("value", "string", required = true, description = "Secret/write-only value."),
                ParamSpec("note", "string", required = false, description = "Non-secret note."),
            ),
            returns = "{entry:{id,key,note,createdAt,hasValue}}",
            example = ex("key" to "PROJECT_TOKEN", "value" to "…", "note" to "Project API"),
        ),
        MethodSpec(
            name = "environments.update",
            description = "Patch environment-variable metadata and optionally replace its write-only value.",
            params = listOf(
                ParamSpec("id", "string", required = true, description = "Entry id."),
                ParamSpec("key", "string", required = false, description = "Replacement key."),
                ParamSpec("value", "string", required = false, description = "Replacement secret; omit to preserve."),
                ParamSpec("note", "string", required = false, description = "Replacement note."),
            ),
            returns = "{entry:{id,key,note,createdAt,hasValue}}",
            example = ex("id" to "…", "note" to "Rotated quarterly"),
        ),
        MethodSpec(
            name = "environments.delete",
            description = "Delete environment-variable metadata and its encrypted value.",
            params = listOf(ParamSpec("id", "string", required = true, description = "Entry id.")),
            returns = "{ok:true}",
            example = ex("id" to "…"),
        ),
        MethodSpec(
            name = "storage.shared.list",
            description = "List fixed /var/minis shared, skills and memory folders.",
            params = emptyList(),
            returns = "{folders:[{id,name,path,writable}]}",
            example = ex(),
        ),
        MethodSpec(
            name = "storage.mounts.list",
            description = "List external folders already authorized through Android SAF. Adding one requires the native picker.",
            params = emptyList(),
            returns = "{mounts:[{id,name,path,hostPath,isWritable,userAllowWrite,effectiveWritable}],count,capacity,canAddFromWeb:false,settingsDeepLink}",
            example = ex(),
        ),
        MethodSpec(
            name = "storage.mounts.rename",
            description = "Rename an existing external mount.",
            params = listOf(ParamSpec("id", "string", true, description = "Mount id."), ParamSpec("name", "string", true, description = "New mount name.")),
            returns = "{mount:{…}}",
            example = ex("id" to "…", "name" to "projects"),
        ),
        MethodSpec(
            name = "storage.mounts.setWritable",
            description = "Set the user's write-policy flag for an existing mount; OS permission still applies.",
            params = listOf(ParamSpec("id", "string", true, description = "Mount id."), ParamSpec("allowWrite", "bool", true, description = "Requested write policy.")),
            returns = "{mount:{…}}",
            example = ex("id" to "…", "allowWrite" to false),
        ),
        MethodSpec(
            name = "storage.mounts.remove",
            description = "Remove a mount and release its persisted SAF grant.",
            params = listOf(ParamSpec("id", "string", true, description = "Mount id."), ParamSpec("confirm", "bool", true, default = false, description = "Must be true.")),
            returns = "{ok:true}",
            example = ex("id" to "…", "confirm" to true),
        ),

        // ── Scheduled tasks (Minis Web mirrors native CRUD + run history) ─────
        MethodSpec(
            name = "scheduled.list",
            description = "List scheduled tasks with next trigger and run count.",
            params = emptyList(),
            returns = "{tasks:[task],count}",
            example = ex(),
        ),
        MethodSpec(
            name = "scheduled.get",
            description = "Read one scheduled task, including its retained run history.",
            params = listOf(ParamSpec("taskId", "string", required = true, description = "Scheduled task identifier.")),
            returns = "{task}",
            example = ex("taskId" to "task-1"),
        ),
        MethodSpec(
            name = "scheduled.create",
            description = "Create and register an Android scheduled task.",
            params = listOf(
                ParamSpec("label", "string", required = true, description = "Display label."),
                ParamSpec("hour", "int", required = true, description = "Local wall-clock hour, 0..23."),
                ParamSpec("minute", "int", required = true, description = "Local wall-clock minute, 0..59."),
                ParamSpec("repeatMode", "string", required = true, description = "ONCE | DAILY | WEEKDAYS | CUSTOM."),
                ParamSpec("customDays", "int[]", required = false, description = "Calendar day values 1..7 for CUSTOM."),
                ParamSpec("prompt", "string", required = false, description = "Prompt for NEW_SESSION / APPEND_TO."),
                ParamSpec("targetMode", "string", required = false, default = "NEW_SESSION", description = "NEW_SESSION | APPEND_TO:<sessionId> | RERUN:<sessionId>:<messageId>."),
                ParamSpec("botId", "string", required = false, description = "Optional owning Bot id; omit for an ordinary routine."),
                ParamSpec("enabled", "bool", required = false, default = true, description = "Whether AlarmManager registration is active."),
            ),
            returns = "{task,created:true}",
            example = ex("label" to "Morning review", "hour" to 9, "minute" to 0, "repeatMode" to "WEEKDAYS", "prompt" to "Review my workspace"),
        ),
        MethodSpec(
            name = "scheduled.update",
            description = "Patch and re-register an existing scheduled task.",
            params = listOf(
                ParamSpec("taskId", "string", required = true, description = "Task id; all task fields are optional patches."),
                ParamSpec("botId", "string", required = false, description = "Change owning Bot id; null clears ownership."),
            ),
            returns = "{task,updated:true}",
            example = ex("taskId" to "task-1", "hour" to 10),
        ),
        MethodSpec(
            name = "scheduled.toggle",
            description = "Enable or disable a scheduled task.",
            params = listOf(
                ParamSpec("taskId", "string", required = true, description = "Scheduled task identifier."),
                ParamSpec("enabled", "bool", required = true, description = "New enabled state."),
            ),
            returns = "{ok:true,task}",
            example = ex("taskId" to "task-1", "enabled" to true),
        ),
        MethodSpec(
            name = "scheduled.delete",
            description = "Permanently remove a scheduled task and cancel its alarm.",
            params = listOf(ParamSpec("taskId", "string", required = true, description = "Scheduled task identifier.")),
            returns = "{ok:true,taskId}",
            example = ex("taskId" to "task-1"),
        ),
        MethodSpec(
            name = "scheduled.run",
            description = "Trigger a scheduled task immediately in the Android background runner.",
            params = listOf(ParamSpec("taskId", "string", required = true, description = "Scheduled task identifier.")),
            returns = "{ok:true,taskId,accepted:true}",
            example = ex("taskId" to "task-1"),
        ),
        MethodSpec(
            name = "scheduled.runs",
            description = "List the retained execution records for one scheduled task.",
            params = listOf(ParamSpec("taskId", "string", required = true, description = "Scheduled task identifier.")),
            returns = "{taskId,runs:[{firedAt,sessionId?,preview?,ok}],count}",
            example = ex("taskId" to "task-1"),
        ),

        // ── Agent settings (web remote: agent.settings.*) ─────────────────────
        MethodSpec(
            name = "agent.settings.get",
            description = "Return the subagent delegation limits (depth cap and per-run timeout). " +
                "Fixed model slots and their ordered entry IDs are managed through provider.slots.get/provider.slots.set; per-model defaults use provider.models.setDefaults.",
            params = emptyList(),
            returns = "{maxDepth, timeoutMinutes}",
            example = ex(),
        ),
        MethodSpec(
            name = "agent.settings.set",
            description = "Update the subagent delegation limits. maxDepth is 1..5, " +
                "timeoutMinutes is 1..30.",
            params = listOf(
                ParamSpec("maxDepth", "int", required = true, description = "Delegation depth cap (1..5)."),
                ParamSpec("timeoutMinutes", "int", required = true, description = "Per-child run timeout in minutes (1..30)."),
            ),
            returns = "{ok:true, maxDepth, timeoutMinutes}",
            example = ex("maxDepth" to 3, "timeoutMinutes" to 10),
        ),
        MethodSpec(
            name = "agent.sessionPermission.get",
            description = "Return one session's Agent execution permission preset (DSH /permission state). " +
                "Distinct from Remote permission capabilities: this gates what the Agent can do, " +
                "not what a browser may invoke.",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Chat session id."),
            ),
            returns = "{sessionId, preset|null}",
            example = ex("sessionId" to "sess-1"),
        ),
        MethodSpec(
            name = "agent.sessionPermission.set",
            description = "Set one session's Agent execution permission preset (workspace-write | " +
                "danger-full-access | null to clear). Emits permission/preset + sandbox/mode + " +
                "approval/policy session events so the DSH permissions projection updates.",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Chat session id."),
                ParamSpec("preset", "string", required = true, description = "preset id or null."),
            ),
            returns = "{sessionId, preset}",
            example = ex("sessionId" to "sess-1", "preset" to "workspace-write"),
        ),

        // ── Agent bars: goal / todo / plan / deliverables ───────────────────
        MethodSpec(
            name = "agent.goal.get",
            description = "Return the session goal (text, active, updatedAt). Empty text = no goal.",
            params = listOf(ParamSpec("sessionId", "string", required = true, description = "Target session id.")),
            returns = "{text, active, updatedAt}",
            example = ex("sessionId" to "6D0F…"),
        ),
        MethodSpec(
            name = "agent.goal.set",
            description = "Set (or clear with blank text) the session goal.",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Target session id."),
                ParamSpec("text", "string", required = true, description = "Goal text; blank clears."),
            ),
            returns = "{text, active, updatedAt}",
            example = ex("sessionId" to "6D0F…", "text" to "完成 Web Remote 设置页"),
        ),
        MethodSpec(
            name = "agent.goal.setActive",
            description = "Pause (active=false) or resume (active=true) the session goal.",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Target session id."),
                ParamSpec("active", "bool", required = true, description = "true=resume, false=pause."),
            ),
            returns = "{text, active, updatedAt}",
            example = ex("sessionId" to "6D0F…", "active" to false),
        ),
        MethodSpec(
            name = "agent.todo.get",
            description = "Return the session todo list.",
            params = listOf(ParamSpec("sessionId", "string", required = true, description = "Target session id.")),
            returns = "{items:[{id,title,status}], updatedAt}",
            example = ex("sessionId" to "6D0F…"),
        ),
        MethodSpec(
            name = "agent.todo.replace",
            description = "Replace the session todo list in one atomic write.",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Target session id."),
                ParamSpec("items", "[{id,title,status}]", required = true, description = "Full replacement list."),
            ),
            returns = "{items:[{id,title,status}], updatedAt}",
            example = ex("sessionId" to "6D0F…", "items" to JSONArray()),
        ),
        MethodSpec(
            name = "agent.plan.get",
            description = "Return plan-mode state (off/plan, plan text).",
            params = listOf(ParamSpec("sessionId", "string", required = true, description = "Target session id.")),
            returns = "{mode, plan, updatedAt}",
            example = ex("sessionId" to "6D0F…"),
        ),
        MethodSpec(
            name = "agent.plan.set",
            description = "Turn plan mode on ('plan') or off ('off') for a session. Soft mode: changes the web banner and composer hint.",
            params = listOf(
                ParamSpec("sessionId", "string", required = true, description = "Target session id."),
                ParamSpec("mode", "string", required = true, description = "'plan' or 'off'."),
                ParamSpec("plan", "string", required = false, description = "Plan text shown in the banner."),
            ),
            returns = "{mode, plan, updatedAt}",
            example = ex("sessionId" to "6D0F…", "mode" to "plan", "plan" to "1. 先看需求 2. 再实现"),
        ),
        MethodSpec(
            name = "agent.deliverables.list",
            description = "Return files the agent modified in this session (newest first).",
            params = listOf(ParamSpec("sessionId", "string", required = true, description = "Target session id.")),
            returns = "{count, files:[{path, action, at}]}",
            example = ex("sessionId" to "6D0F…"),
        ),
        MethodSpec(
            name = "agent.deliverables.clear",
            description = "Clear the session deliverables list.",
            params = listOf(ParamSpec("sessionId", "string", required = true, description = "Target session id.")),
            returns = "{ok:true}",
            example = ex("sessionId" to "6D0F…"),
        ),
)
