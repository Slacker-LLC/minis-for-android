package com.openminis.app.remote

import android.content.Context
import com.openminis.app.tools.SessionPermissionStore

/**
 * Single Android-authoritative Agent Preset registry (P-presets).
 *
 * Both the Android App and the Web Remote read and write THIS registry only.
 * There is deliberately no second preset store (no SharedPreferences pair in
 * the Web, no preset list in the client plugin): a preset chosen anywhere is
 * persisted here and applied to the real Android runtime.
 *
 * Canonical built-in ids (stable, never renamed by display language):
 *
 *  - `standard` 标准模式 — full current Agent capabilities (default).
 *  - `code`     PTC 模式 — keeps the full standard capability set; the
 *    Android runtime does NOT yet ship a Code Mode SDK executor, so `code`
 *    deliberately does NOT fake one: it applies the composition-preference
 *    prompt configuration only, and the limitation is stated in its
 *    description rather than hidden behind a label.
 *  - `minimal`  极简模式 — genuinely reduced runtime: only the persistent
 *    Bash shell and the file read/write/edit trio are exposed to the model
 *    (tool configuration + prompt configuration both change).
 *
 * Legacy ids from earlier versions remain resolvable as aliases so existing
 * sessions keep their stored value (`default` → `standard`,
 * `workspace-sandboxed` → a standard-permission alias whose permission gate
 * is preserved).
 */
object AgentPresetRegistry {

    /** Toolset a session exposes to the model. */
    enum class Toolset { FULL, CORE }

    data class Preset(
        val id: String,
        val trust: String, // "system" | "user"
        val isDefault: Boolean,
        val name: String,
        val description: String,
        /** Permission preset this preset forces; null = leave session as-is. */
        val permission: String?,
        /** Real tool configuration applied by the runtime. */
        val toolset: Toolset = Toolset.FULL,
        /** Prompt configuration: additional runtime prompt section (null = none). */
        val promptSection: String? = null,
    )

    private const val PREFS = "minis_agent_presets"
    private fun sessionKey(sessionId: String) = "session_$sessionId"
    private const val DEFAULT_KEY = "default_for_new_sessions"

    val builtins: List<Preset> = listOf(
        Preset(
            id = "standard",
            trust = "system",
            isDefault = true,
            name = "标准模式",
            description = "功能完整的通用编码 Agent：文件、Shell、浏览器、Goal、计划、子代理、Jobs、Skills 与 MCP 全量可用",
            permission = null,
            toolset = Toolset.FULL,
            promptSection = null,
        ),
        Preset(
            id = "code",
            trust = "system",
            isDefault = false,
            name = "PTC 模式",
            description = "保留标准模式的全部能力，并通过 Code Mode SDK 呈现工具，让模型用一个 TypeScript 程序组合多步操作（当前 Android Runtime 尚未内置 Code Mode 执行器，本模式先应用组合式提示配置）",
            permission = null,
            toolset = Toolset.FULL,
            promptSection = """
                当前预设为 PTC（Program-as-Tool Composition）模式：对于需要多步工具操作的复杂任务，优先把可组合的步骤写成一段脚本/程序一次性执行（例如通过 shell 编写并运行一个脚本，或用一次性文件编辑完成批量修改），而不是逐条发出顺序独立的工具调用。"""
                .trimIndent(),
        ),
        Preset(
            id = "minimal",
            trust = "system",
            isDefault = false,
            name = "极简模式",
            description = "仅提供持久 Bash 与文件读写编辑等核心编码能力，减少暴露给模型的其他工具与提示词，适合长期稳定运行",
            permission = null,
            toolset = Toolset.CORE,
            promptSection = """
                当前预设为极简模式：只提供核心编码工具（持久 Shell、文件读取、文件写入、文件编辑）。不要假设存在浏览器、Goal、子代理、Job 或其他工具；对无法完成的需求，应如实说明能力边界。"""
                .trimIndent(),
        ),
    )

    /** Legacy → canonical id migration (existing sessions keep working). */
    private val legacyAliases = mapOf(
        "default" to "standard",
        "workspace-sandboxed" to "standard",
    )

    fun get(id: String): Preset? {
        val canonical = legacyAliases[id] ?: return builtins.firstOrNull { it.id == id }
        return builtins.firstOrNull { it.id == canonical }
    }

    fun isKnownPreset(id: String): Boolean = get(id) != null

    fun list(): List<Preset> = builtins

    /**
     * Apply a preset to one session for real: persist the selection and apply
     * permission + runtime tool configuration. Returns the applied preset, or
     * null when unknown.
     */
    fun applyToSession(context: Context, sessionId: String, presetId: String): Preset? {
        val preset = get(presetId) ?: return null
        if (sessionId.isNotBlank()) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(sessionKey(sessionId), preset.id).apply()
        }
        // permission == null deliberately clears any earlier preset.
        SessionPermissionStore.setPreset(context, sessionId, preset.permission)
        return preset
    }

    /**
     * The preset a session actually runs on. Never-set sessions use the
     * default for new sessions (which resolves to `standard`), so the value
     * is never null — both clients read the same effective preset.
     */
    fun presetForSession(context: Context, sessionId: String): Preset {
        if (sessionId.isNotBlank()) {
            val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(sessionKey(sessionId), null)
            if (id != null) return get(id) ?: defaultForNewSessions(context)
        }
        return defaultForNewSessions(context)
    }

    fun defaultForNewSessions(context: Context): Preset {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(DEFAULT_KEY, "standard") ?: "standard"
        return get(id) ?: get("standard")!!
    }

    fun setDefaultForNewSessions(context: Context, presetId: String): Boolean {
        val preset = get(presetId) ?: return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(DEFAULT_KEY, preset.id).apply()
        return true
    }
}
