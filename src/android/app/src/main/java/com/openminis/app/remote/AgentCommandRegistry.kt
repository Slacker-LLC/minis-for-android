package com.openminis.app.remote

import android.content.Context
import com.openminis.app.debug.ChatMutationMethods
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.tools.AgentStateStore
import com.openminis.app.tools.SessionPermissionStore
import com.openminis.app.ui.chat.SessionEventHub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Single Android-authoritative slash-command registry for the Web Remote
 * surface (P-commands-unify).
 *
 * BEFORE this class the Android app and the Web Host each owned a different
 * command directory (Android: clear/compact/memory/thinking + skills; Web:
 * model/permission/goal/feedback/export). Both surfaces must expose the SAME
 * business commands with the SAME meaning and the SAME execution, because the
 * browser only ever talks to the Android Runtime.
 *
 * Architecture:
 *
 *     Android Runtime (ChatViewModel / AgentStateStore / …)
 *                        │
 *                AgentCommandRegistry
 *                   │            │
 *            App slash menu   Web commands/list
 *                    │            │
 *                 unified `/` menu (same names, same dispatch)
 *
 * The Web client keeps the DSH `command.decorate` mechanism for UI-only
 * popups (model selector, permission presets) — decoration is NOT a second
 * command, it only hangs UI on a bare invocation of a host command. Nothing
 * here may be re-listed in `web/minis-client-plugin` or a DSH client plugin:
 * this class is the one directory.
 */
object AgentCommandRegistry {

    /** One registry entry: stable name, description, optional input hint. */
    data class Entry(
        val name: String,
        val description: String,
        val hint: String? = null,
    )

    /**
     * The canonical business commands (Context-free, JVM-testable).
     * `directory()` appends the session catalogs (skills/MCP) to this base.
     * The Web's commands/list and the App's slash menu both render THIS list
     * — there is no second list anywhere.
     */
    val baseEntries: List<Entry> = listOf(
        Entry(
            name = "model",
            description = "查看或切换当前会话的模型与思考强度",
            hint = "可选: 模型 id，或 `off`/`low`/`medium`/`high`/`xhigh` 设置思考强度",
        ),
        Entry(
            name = "permission",
            description = "切换当前会话的 Agent 执行权限预设",
            hint = "workspace-write | danger-full-access",
        ),
        Entry(
            name = "goal",
            description = "查看或设置当前会话的 Goal",
            hint = "可选: 目标描述文本",
        ),
        Entry(
            name = "plan",
            description = "进入或退出计划模式",
            hint = "off 退出计划模式，其余/空为进入",
        ),
        Entry(
            name = "compact",
            description = "压缩当前会话上下文",
        ),
        Entry(
            name = "clear",
            description = "清空当前会话消息（保留工作区文件）",
        ),
        Entry(
            name = "memory",
            description = "切换当前会话的记忆写入开关",
        ),
        Entry(
            name = "thinking",
            description = "切换当前会话的思考强度开关",
        ),
        Entry(
            name = "feedback",
            description = "查看当前会话的消息反馈状态",
        ),
        Entry(
            name = "export",
            description = "导出当前会话的 JSON 日志",
        ),
    )

    /**
     * The unified host command directory in display order. Every entry here
     * MUST have a real handler in [execute] — a menu-only stub is a lie to the
     * user and is deliberately not listed.
     */
    fun directory(context: Context, sessionId: String): List<Entry> = buildList {
        addAll(baseEntries)
        // Skills / MCP are projected as informational entries backed by the
        // real catalogs — the Web `/` menu therefore shows what the Android
        // runtime actually has, not a hard-coded second list.
        val skills = runCatching { skillNames(context, sessionId) }.getOrDefault(emptyList())
        for (name in skills) {
            add(Entry(
                name = name,
                description = "调用该 Skill（填入 composer 后发送）",
            ))
        }
        val mcp = runCatching { mcpServerNames(context) }.getOrDefault(emptyList())
        if (mcp.isNotEmpty()) {
            add(Entry(
                name = "mcp",
                description = "查看当前已启用的 MCP 服务器",
                hint = "列表指令（不带参数）",
            ))
        }
    }

    /**
     * Execute one unified host command against the real Android runtime.
     * Returns a human-readable outcome; `ok=false` is an honest refusal (the
     * caller renders it as an error node, never a silent no-op).
     */
    suspend fun execute(
        context: Context,
        sessionId: String,
        name: String,
        arg: String,
    ): Outcome = withContext(Dispatchers.Default) {
        try {
            when (name) {
                "model" -> commandModel(context, sessionId, arg)
                "permission" -> commandPermission(context, sessionId, arg)
                "goal" -> commandGoal(context, sessionId, arg)
                "plan" -> commandPlan(context, sessionId, arg)
                "compact" -> commandCompact(context, sessionId)
                "clear" -> commandClear(context, sessionId)
                "memory" -> commandMemory(context, sessionId)
                "thinking" -> commandThinking(context, sessionId)
                "feedback" -> Outcome(true, commandFeedbackText(context, sessionId))
                "export" -> Outcome(true, commandExport(context, sessionId))
                "mcp" -> Outcome(true, mcpSummaryText(context))
                else -> {
                    // Skills are fill-the-composer typing aids on the App and
                    // here: the host acknowledges existence, the model learns
                    // the skill from the injected fragment on send.
                    if (skillNames(context, sessionId).contains(name)) {
                        Outcome(true, "已填入 /$name —— 发送后模型将通过 SKILL.md 片段获得该能力")
                    } else {
                        Outcome(false, "unknown command: /$name")
                    }
                }
            }
        } catch (e: Exception) {
            Outcome(false, e.message ?: "command failed")
        }
    }

    data class Outcome(val ok: Boolean, val text: String = "")

    // ------------------------------------------------------------- handlers

    private suspend fun commandModel(context: Context, sessionId: String, arg: String): Outcome {
        if (arg.isEmpty()) {
            val status = runCatching {
                ChatMutationMethods.status(context, JSONObject().put("sessionId", sessionId))
            }.getOrNull()
            val model = status?.optString("modelName", "").orEmpty()
            val level = status?.optString("thinkingLevel", "").orEmpty()
            return Outcome(true, "当前模型: ${model.ifEmpty { "(未设置)" }}${if (level.isNotEmpty()) " · 思考强度: $level" else ""}")
        }
        val effort = when (arg.lowercase()) {
            "off", "low", "medium", "high", "xhigh" -> arg.lowercase()
            else -> null
        }
        if (effort != null) {
            ChatMutationMethods.selectThinkingLevel(
                context,
                JSONObject().put("sessionId", sessionId).put("thinkingLevel", effort),
            )
            return Outcome(true, "思考强度已切换为 $effort")
        }
        val result = ChatMutationMethods.selectModel(
            context,
            JSONObject().put("sessionId", sessionId).put("modelEntryId", arg),
        )
        val chosen = result.optString("modelEntryId", arg).ifEmpty { arg }
        return Outcome(true, "模型已切换: $chosen")
    }

    private fun commandPermission(context: Context, sessionId: String, arg: String): Outcome {
        val preset = arg.lowercase()
        if (preset.isNotEmpty() && !SessionPermissionStore.isKnownPreset(preset)) {
            return Outcome(false, "未知权限预设: $preset (可用: workspace-write | danger-full-access)")
        }
        val effective = preset.ifEmpty { SessionPermissionStore.preset(context, sessionId) ?: "workspace-write" }
        SessionPermissionStore.setPreset(context, sessionId, preset.ifEmpty { null })
        val sandboxMode = if (effective == SessionPermissionStore.DANGER_FULL_ACCESS) "danger-full-access" else "workspace-write"
        val approvalPolicy = if (effective == SessionPermissionStore.DANGER_FULL_ACCESS) "never" else "ask"
        SessionEventHub.append(sessionId, "permission/preset", JSONObject().put("preset", effective))
        SessionEventHub.append(sessionId, "sandbox/mode", JSONObject().put("mode", sandboxMode))
        SessionEventHub.append(sessionId, "approval/policy", JSONObject().put("policy", approvalPolicy))
        return if (preset.isEmpty()) {
            Outcome(true, "当前会话权限: $effective (未预设,按默认工作区写入)")
        } else {
            Outcome(true, "当前会话权限已切换为 $effective；文件写入门禁已生效")
        }
    }

    private fun commandGoal(context: Context, sessionId: String, arg: String): Outcome {
        val current = AgentStateStore.goalGet(sessionId)
        if (arg.isEmpty()) {
            if (current.text.isBlank()) return Outcome(true, "当前会话未设置 Goal")
            return Outcome(true, "Goal (${current.phase}): ${current.text} (rounds ${current.maxGoalRounds})")
        }
        val goal = AgentStateStore.goalSet(sessionId, arg)
        appendGoalChange(sessionId, "create", goal)
        return Outcome(true, "Goal 已设置: ${goal.text}")
    }

    /** `/plan` — real Android plan-mode state, folded for the Web plan chip. */
    private fun commandPlan(context: Context, sessionId: String, arg: String): Outcome {
        val mode = if (arg.trim().equals("off", ignoreCase = true)) "off" else "plan"
        val plan = AgentStateStore.planSet(sessionId, mode, arg.trim().takeIf { mode == "plan" && arg.isNotBlank() } ?: "")
        val active = plan.mode == "plan"
        // The DSH client folds /plan state from (command/run + plan/mode)
        // events; emitting both keeps the browser chip and the Android
        // runtime on one state. commands/execute already appends command/run
        // and command/done around this handler.
        SessionEventHub.append(sessionId, "plan/mode", JSONObject().put("active", active))
        return Outcome(
            true,
            if (active) "已进入计划模式${if (plan.plan.isNotBlank()) "；计划: ${plan.plan}" else ""}" else "已退出计划模式",
        )
    }

    private suspend fun commandCompact(context: Context, sessionId: String): Outcome {
        val result = HeadlessChatRunner.compact(
            context, sessionId, wait = false, timeoutMs = 900_000L,
        )
        return when (result.status) {
            "Complete", "Compacted" -> Outcome(true, "会话已压缩")
            else -> Outcome(false, "压缩失败: ${result.error ?: result.status}")
        }
    }

    private suspend fun commandClear(context: Context, sessionId: String): Outcome = withContext(Dispatchers.Main) {
        val vm = HeadlessChatRunner.viewModelForCommand(context, sessionId)
        if (vm != null) {
            vm.clearChat()
            Outcome(true, "会话已清空（工作区文件已保留）")
        } else {
            Outcome(false, "会话未加载，无法清空")
        }
    }

    private suspend fun commandMemory(context: Context, sessionId: String): Outcome =
        withContext(Dispatchers.Main) {
            val vm = HeadlessChatRunner.viewModelForCommand(context, sessionId)
            if (vm != null) {
                val enabled = vm.toggleMemoryForCommand()
                Outcome(true, if (enabled) "记忆写入已开启" else "记忆写入已关闭")
            } else {
                Outcome(false, "会话未加载，无法切换记忆")
            }
        }

    private suspend fun commandThinking(context: Context, sessionId: String): Outcome =
        withContext(Dispatchers.Main) {
            val vm = HeadlessChatRunner.viewModelForCommand(context, sessionId)
            if (vm != null) {
                vm.toggleThinkingForCommand()
                Outcome(true, "思考强度已切换")
            } else {
                Outcome(false, "会话未加载，无法切换思考")
            }
        }

    private fun commandFeedbackText(context: Context, sessionId: String): String {
        val items = com.openminis.app.tools.MessageFeedbackStore.listForSession(context, sessionId)
        if (items.isEmpty()) return "当前会话暂无消息反馈"
        val positive = items.count { it.second.rating == "positive" }
        val negative = items.count { it.second.rating == "negative" }
        return "当前会话反馈: $positive 个赞同 / $negative 个反对 (共 ${items.size} 条)"
    }

    private suspend fun commandExport(context: Context, sessionId: String): String {
        val target = java.io.File(context.cacheDir, "session_export_$sessionId.json").apply { parentFile?.mkdirs() }
        val replay = runCatching { HeadlessChatRunner.sessionEvents(context, sessionId, null) }.getOrNull()
        val array = JSONArray()
        replay?.events?.forEach { array.put(it.toEventJson()) }
        target.writeText(JSONObject()
            .put("sessionId", sessionId)
            .put("exportedAt", System.currentTimeMillis())
            .put("events", array).toString(2))
        return "会话已导出: ${target.absolutePath}"
    }

    // ------------------------------------------------------------ catalogs

    private fun skillNames(context: Context, sessionId: String): List<String> = runCatching {
        val app = context.applicationContext as? com.openminis.app.MinisApp ?: return emptyList()
        app.skillRepository.skills.value
            .filter { it.isEnabled && app.skillRepository.isEnabledForSession(it.id, sessionId) }
            .map { it.name.lowercase() }
    }.getOrDefault(emptyList())

    private fun mcpServerNames(context: Context): List<String> = runCatching {
        val app = context.applicationContext as? com.openminis.app.MinisApp ?: return emptyList()
        app.mcpRepository.servers.value.filter { it.enabled }.map { it.id }
    }.getOrDefault(emptyList())

    private fun mcpSummaryText(context: Context): String {
        val servers = mcpServerNames(context)
        return if (servers.isEmpty()) "当前未启用 MCP 服务器" else "已启用 MCP: ${servers.joinToString(", ")}"
    }

    private fun appendGoalChange(sessionId: String, operation: String, goal: AgentStateStore.Goal) {
        SessionEventHub.append(sessionId, "goal/change", JSONObject().apply {
            put("operation", operation)
            put("goal", JSONObject().apply {
                put("id", goal.id)
                put("revision", goal.revision)
                put("objective", goal.text)
                put("phase", goal.phase)
                put("maxGoalRounds", goal.maxGoalRounds)
            })
            put("roundsStarted", 0)
            put("createdAt", goal.createdAt)
            put("updatedAt", goal.updatedAt)
        })
    }
}
