package com.openminis.app.prompt

import android.content.Context
import com.openminis.app.agent.SystemPromptBuilder

/**
 * [T-system-prompt-modules] Single entry point for the agent system prompt.
 *
 * Composition, in order:
 *
 *  1. **Identity layer** — `SystemPromptBuilder.identitySection()`: the fixed
 *     "You are <name> ..." sentence plus the SOUL.md personality/style block.
 *     Authored in Settings -> Soul (SOUL.md), not in this package.
 *  2. **User system prompt** — [CustomPromptStore]: the device owner's own text
 *     from Settings -> System prompt. Injected here and declared to outrank every
 *     other authored section (personality, style, presets, bots, sessions).
 *  3. **Editable modules** — [PromptModuleRegistry], each resolvable to a shipped
 *     default and an optional user override ([PromptModuleStore]).
 *  4. **Per-turn runtime fragments** — skills, MCP disclosure, GLOBAL.md, recent
 *     daily memory and the runtime-context footer. These are derived from live
 *     state and stay in ChatViewModel.buildSystemPrompt().
 *
 * Steps 1+2 are the "static head" that prompt caching relies on staying
 * byte-stable across requests.
 */
object AgentSystemPrompt {

    /** Identity + user prompt + editable modules, i.e. everything before the runtime fragments. */
    fun base(context: Context, memoryOn: Boolean): String =
        SystemPromptBuilder.identitySection(context) +
            customPromptSection(context) +
            modulesSection(context, memoryOn)

    /**
     * The device owner's own system prompt, or an empty string when unset.
     *
     * Rendered immediately after the identity sentence and before every module,
     * with a header that states its precedence: this is the box the user types
     * into, so it must not be drowned by the shipped wording underneath it.
     * Empty text keeps the assembled prompt byte-identical to a build without
     * this feature.
     */
    fun customPromptSection(context: Context): String =
        customPromptSection(CustomPromptStore.text(context))

    /** Context-free seam for tests; blank text renders nothing. */
    internal fun customPromptSection(text: String?): String {
        val body = text?.trim().orEmpty()
        if (body.isEmpty()) return ""
        return CUSTOM_PROMPT_HEADER + "\n" + body + "\n\n"
    }

    private const val CUSTOM_PROMPT_HEADER =
        "User system prompt (highest priority - written by the device owner). " +
            "Where the personality, response style, preset, bot or session guidance below conflicts with it, follow it."

    /** The editable modules only (no identity layer, no runtime fragments). */
    fun modulesSection(context: Context, memoryOn: Boolean): String =
        modulesSection(PromptModuleStore.snapshots(context), memoryOn)

    /**
     * Context-free seam for tests and for callers that already hold resolved
     * snapshots: same rule as the Context overload — a switched-off module and a
     * module with blank text are both skipped.
     */
    internal fun modulesSection(
        snapshots: List<PromptModuleStore.ModuleSnapshot>,
        memoryOn: Boolean,
    ): String {
        val byId = snapshots.associateBy { it.module.id }
        return SystemPromptComposer.compose(memoryOn = memoryOn) { module ->
            val snapshot = byId[module.id] ?: return@compose null
            if (!snapshot.isEnabled) null else snapshot.text
        }
    }
}
