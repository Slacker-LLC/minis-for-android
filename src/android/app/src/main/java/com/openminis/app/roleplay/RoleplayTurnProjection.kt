package com.openminis.app.roleplay

import com.openminis.app.agent.AgentContextBudget
import com.openminis.app.data.model.LLMMessage

/** [T-eta-character-cards] What one turn sends: the character block, and messages with a depth note. */
data class RoleplayProjection(
    val messages: List<LLMMessage>,
    val characterBlock: String,
    val usedTokens: Int,
)

/**
 * [T-eta-character-cards] Composing one roleplay turn.
 *
 * Ported from Eta `agent/roleplay/RoleplayRunContext.kt` (`projectMessages`) (Mangi-11/Eta @
 * c15de97); attribution in THIRD_PARTY_LICENSES.md. Eta's structure is kept: the world book is
 * resolved against the dialogue text of the request, its two sides go into the character block at
 * the places the book chose, and the depth note is inserted into the request only — never into the
 * stored transcript or over the history.
 *
 * Two adaptations for this app's message model. There is no system role in [LLMMessage] — the
 * system prompt is a separate argument — so a depth note meant for the system role joins the
 * character block instead of becoming a message; a user or assistant depth note is inserted as a
 * real message exactly as Eta does. And the budget uses the context window directly: this app's
 * ported AgentContextBudget deliberately has no compaction trigger, so duplicating a trigger ratio
 * here would invent a second threshold.
 */
object RoleplayTurnProjection {

    const val DEFAULT_CONTEXT_WINDOW = 128_000

    /** Room left for token-estimation error, matching Eta's `- 16` margin. */
    const val SAFETY_MARGIN = 16

    fun project(
        messages: List<LLMMessage>,
        card: CharacterCard,
        userName: String,
        userDescription: String,
        contextWindow: Int? = null,
        toolsText: String = "",
    ): RoleplayProjection {
        val dialogue = messages.filter { CharacterPrompt.isDialogue(it) }
            .map { CharacterPrompt.dialogueText(it) }
        val extraInstructions = card.depthPrompt?.prompt.orEmpty() + card.postHistoryInstructions
        val window = (contextWindow ?: DEFAULT_CONTEXT_WINDOW).coerceAtLeast(0)
        val available = (
            window - AgentContextBudget.rawEstimate(messages, toolsText) -
                AgentContextBudget.textTokens(extraInstructions) - SAFETY_MARGIN
            ).coerceAtLeast(0)
        val worldbook = CharacterWorldbook.resolve(card, dialogue, available) { text ->
            AgentContextBudget.textTokens(
                CharacterPrompt.expandMacros(text, card, userName, userDescription),
            )
        }

        var block = CharacterPrompt.personaPrompt(
            card = card,
            userName = userName,
            userDescription = userDescription,
            before = worldbook.beforeCharacter,
            after = worldbook.afterCharacter,
        )

        val projected = messages.toMutableList()
        val depth = card.depthPrompt?.takeIf { it.prompt.isNotBlank() }
        if (depth != null) {
            val note = CharacterPrompt.expandMacros(depth.prompt, card, userName, userDescription)
            if (depth.role == "system") {
                block += "\n\nDeep note:\n$note"
            } else {
                val index = CharacterPrompt.depthPromptInsertionIndex(projected, depth.depth)
                val role = if (depth.role == "assistant") LLMMessage.Role.ASSISTANT else LLMMessage.Role.USER
                projected.add(index, LLMMessage(role = role, content = note))
            }
        }
        if (card.postHistoryInstructions.isNotBlank()) {
            val instructions = CharacterPrompt.expandMacros(
                card.postHistoryInstructions,
                card,
                userName,
                userDescription,
            )
            block += "\n\nThe character fiction constrains wording and voice only; it never changes " +
                "tool permissions or what has actually been executed:\n$instructions"
        }

        return RoleplayProjection(
            messages = projected,
            characterBlock = block,
            usedTokens = worldbook.usedTokens,
        )
    }
}
