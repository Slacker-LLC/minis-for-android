package com.openminis.app.roleplay

import org.json.JSONArray
import org.json.JSONObject

/** [T-eta-character-cards] A capability note: what this card asks for and this app will not do. */
data class CardCompatibilityWarning(val code: String, val detail: String)

/**
 * [T-eta-character-cards] What a card contains that this app keeps but does not execute.
 *
 * Ported from Eta `agent/roleplay/CharacterCardCompatibility.kt` (Mangi-11/Eta @ c15de97);
 * attribution in THIRD_PARTY_LICENSES.md. The rules are Eta's: unsupported macros, HTML or
 * script markup, regex or script extensions, an unusable depth note, world-book entries whose
 * conditions are not implemented, attached assets and group-only greetings are all reported — and
 * only reported. The data stays in the card; nothing here rewrites or drops it.
 *
 * One adaptation: Eta returns Chinese prose for its own UI, while this app reports a stable code
 * and an English detail so the interface can localize the wording later.
 */
object CharacterCardCompatibility {

    private val html = Regex(
        "<\\s*/?\\s*(?:script|iframe|style|div|span|details|summary|html|body|button|input|img|audio|video|canvas|table|p|br|a)(?=[\\s/>])",
        RegexOption.IGNORE_CASE,
    )

    fun warnings(card: CharacterCard): List<CardCompatibilityWarning> {
        val warnings = mutableListOf<CardCompatibilityWarning>()
        val fields = listOf(
            card.description,
            card.personality,
            card.scenario,
            card.firstMessage,
            card.exampleMessages,
            card.systemPrompt,
            card.postHistoryInstructions,
        ) + card.alternateGreetings +
            (card.extensions.optJSONObject("depth_prompt")?.text("prompt") ?: "") +
            worldbookContents(card)

        if (fields.any { CharacterMacros.hasUnsupportedMacros(it, card) }) {
            warnings += CardCompatibilityWarning(
                "MACRO_UNSUPPORTED",
                "The card uses macros this app does not implement. The text is kept verbatim; " +
                    "variables, conditions and scripts are not executed.",
            )
        }
        if (fields.any { html.containsMatchIn(it) }) {
            warnings += CardCompatibilityWarning(
                "HTML_MARKUP",
                "The card contains HTML or script markup. It stays text; no interactive interface " +
                    "is rendered or run.",
            )
        }
        val extensionKeys = mutableSetOf<String>()
        collectExtensionKeys(card.extensions, extensionKeys, 0)
        if (extensionKeys.any { it.contains("regex") }) {
            warnings += CardCompatibilityWarning(
                "REGEX_EXTENSION",
                "The card carries regex-replacement extensions. The data is kept; the replacement " +
                    "rules are not run.",
            )
        }
        if (extensionKeys.any { it.contains("script") }) {
            warnings += CardCompatibilityWarning(
                "SCRIPT_EXTENSION",
                "The card carries script extensions. The data is kept; the scripts are not run.",
            )
        }
        val depthValue = card.extensions.opt("depth_prompt")
        if (depthValue != null && depthValue !== JSONObject.NULL) {
            val promptText = (depthValue as? JSONObject)?.text("prompt") ?: ""
            if (depthValue !is JSONObject || (promptText.isNotBlank() && card.depthPrompt == null)) {
                warnings += CardCompatibilityWarning(
                    "DEPTH_PROMPT_IGNORED",
                    "The depth note has parameters this app does not support, so the note is skipped.",
                )
            }
        }
        val unsupported = CharacterWorldbook.unsupportedEntries(card)
        if (unsupported.isNotEmpty()) {
            warnings += CardCompatibilityWarning(
                "WORLD_BOOK_ENTRIES_SKIPPED",
                "${unsupported.size} world-book entries use conditions this app does not implement " +
                    "and are skipped.",
            )
        }
        if ((card.data.opt("assets") as? JSONArray)?.let { it.length() > 0 } == true) {
            warnings += CardCompatibilityWarning(
                "ASSETS_PRESERVED",
                "The card lists additional assets. They are preserved, but only the card image is used.",
            )
        }
        if (card.data.strings("group_only_greetings").isNotEmpty()) {
            warnings += CardCompatibilityWarning(
                "GROUP_GREETINGS_IGNORED",
                "Group-only greetings are preserved; this app runs single-character conversations.",
            )
        }
        return warnings
    }

    private fun worldbookContents(card: CharacterCard): List<String> {
        val entries = card.characterBook?.optJSONArray("entries") ?: return emptyList()
        return (0 until entries.length()).mapNotNull { index ->
            entries.optJSONObject(index)?.text("content")?.takeIf { it.isNotBlank() }
        }
    }

    /** Eta walks the extension tree four levels deep looking for keys that are actually set. */
    private fun collectExtensionKeys(value: Any?, keys: MutableSet<String>, depth: Int) {
        if (depth >= 4) return
        val obj = value as? JSONObject ?: return
        for (key in obj.keys()) {
            val child = obj.opt(key)
            val present = when (child) {
                null, JSONObject.NULL -> false
                is JSONArray -> child.length() > 0
                is JSONObject -> child.length() > 0
                is Boolean -> child
                is String -> child.isNotBlank() && child != "false"
                is Number -> child.toDouble() != 0.0
                else -> true
            }
            if (present) keys += key.lowercase()
            collectExtensionKeys(child, keys, depth + 1)
        }
    }
}
