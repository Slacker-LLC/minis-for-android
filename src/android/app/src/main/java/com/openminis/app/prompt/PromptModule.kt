package com.openminis.app.prompt

/**
 * [T-system-prompt-modules] One independently editable section of the agent
 * system prompt.
 *
 * Before this module existed the static part of the Android system prompt was a
 * single Kotlin string literal inside `ChatViewModel.buildSystemPrompt()`: every
 * wording change needed a rebuild, and nothing could be inspected or adjusted
 * from the app. Each section now lives in its own file under `assets/prompts/`,
 * registered here in assembly order, and can be overridden from
 * Settings -> System prompt or through `minis-config`.
 *
 * Defaults stay in assets so an app update ships improved wording to every user
 * who has not customized that section. An override is a separate file and
 * always wins; deleting the override returns the module to the shipped default.
 */
data class PromptModule(
    /**
     * Stable id. Doubles as the override file name and as the
     * `prompt.<id>.text` / `prompt.<id>.enabled` config path, so it must not
     * change once shipped (an id change orphans saved overrides).
     */
    val id: String,
    /** Title rendered in the Settings editor. */
    val title: String,
    /** One-line explanation of what the section is for. */
    val description: String,
    /** Settings group this module is listed under. */
    val group: String,
    /** File name under `assets/prompts/` holding the shipped default text. */
    val assetName: String = "$id.md",
    /** Runtime condition that decides whether this module is included. */
    val gate: PromptGate = PromptGate.ALWAYS,
    /** Whitespace inserted before this module when another module precedes it. */
    val gapBefore: PromptGap = PromptGap.BLANK_LINE,
)

/** Runtime condition that decides whether a module participates in the prompt. */
enum class PromptGate {
    ALWAYS,

    /** Only injected while memory injection/tools are enabled for the session. */
    MEMORY_ON,

    /** Only injected while memory is disabled for the session. */
    MEMORY_OFF,
    ;

    fun applies(memoryOn: Boolean): Boolean = when (this) {
        ALWAYS -> true
        MEMORY_ON -> memoryOn
        MEMORY_OFF -> !memoryOn
    }
}

/**
 * Whitespace this module contributes before itself.
 *
 * `BLANK_LINE` starts a new paragraph. `TIGHT` continues the previous paragraph
 * and is used where the prompt deliberately runs lines together (the memory tool
 * bullets extending the `browser_use` bullet list, the inline-media notes
 * following the `minis://` scheme block, the interactive-terminal note following
 * the Android CLI list). Keeping the distinction is what makes the assembled
 * default prompt byte-identical to the pre-module prompt.
 */
enum class PromptGap { BLANK_LINE, TIGHT }
