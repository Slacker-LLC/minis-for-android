package com.openminis.app.prompt

/**
 * [T-system-prompt-modules] Assembles the editable modules into the static head
 * of the agent system prompt.
 *
 * Pure string work on purpose: the prompt is built synchronously on the send
 * path, so composition must not read the asset tree or the filesystem. Module
 * text arrives through [textFor], which the caller resolves from
 * [PromptModuleStore]'s in-memory snapshot.
 *
 * Composition rules:
 *  - modules render in registry order;
 *  - a module whose gate does not match, that is switched off, or whose text is
 *    blank is skipped entirely (its gap goes with it — no stray blank lines);
 *  - otherwise the module contributes its `gapBefore` (blank line or tight
 *    newline) and then its trimmed text;
 *  - the first module that renders contributes no gap.
 *
 * With untouched defaults this reproduces the pre-module prompt byte for byte
 * for both memory states; `SystemPromptComposerTest` pins that against the
 * checked-in legacy fixtures.
 */
object SystemPromptComposer {

    fun compose(
        modules: List<PromptModule> = PromptModuleRegistry.modules,
        memoryOn: Boolean,
        textFor: (PromptModule) -> String?,
    ): String {
        val out = StringBuilder()
        var wroteAny = false
        for (module in modules) {
            if (!module.gate.applies(memoryOn)) continue
            val body = textFor(module)?.trim().orEmpty()
            if (body.isEmpty()) continue
            if (wroteAny) {
                out.append(if (module.gapBefore == PromptGap.TIGHT) "\n" else "\n\n")
            }
            out.append(body)
            wroteAny = true
        }
        return out.toString()
    }
}
