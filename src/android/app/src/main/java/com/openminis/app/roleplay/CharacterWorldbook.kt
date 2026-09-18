package com.openminis.app.roleplay

import org.json.JSONArray
import org.json.JSONObject

/** [T-eta-character-cards] What one turn's world-book scan selected, split by insertion point. */
data class WorldbookProjection(
    val beforeCharacter: String = "",
    val afterCharacter: String = "",
    val usedTokens: Int = 0,
)

/**
 * [T-eta-character-cards] Selecting lore entries for a turn.
 *
 * Ported from Eta `agent/roleplay/CharacterWorldbook.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. The rules are Eta's, unchanged: only entries with no unsupported
 * condition and a non-blank body take part, the scan window is the last `scan_depth` messages,
 * `selective` entries additionally need a secondary key, recursion can let one matched entry
 * reveal another (bounded, because the loop only reruns while the matched set grew), and the
 * token budget is enforced by re-measuring the whole projection per candidate so a very large
 * entry is dropped instead of overflowing the window.
 */
object CharacterWorldbook {

    fun unsupportedEntries(card: CharacterCard): List<UnsupportedWorldbookEntry> {
        val entries = card.characterBook?.optJSONArray("entries") ?: return emptyList()
        return (0 until entries.length()).mapNotNull { index ->
            val entry = entries.optJSONObject(index) ?: return@mapNotNull null
            val reasons = CharacterWorldbookSupport.reasons(entry)
            UnsupportedWorldbookEntry(index, reasons).takeIf { reasons.isNotEmpty() }
        }
    }

    fun resolve(
        card: CharacterCard,
        messages: List<String>,
        inputTokenBudget: Int,
        estimateTokens: (String) -> Int,
    ): WorldbookProjection {
        val book = card.characterBook ?: return WorldbookProjection()
        val available = inputTokenBudget.coerceAtLeast(0)
        val budget = (intOrNull(book.opt("token_budget")) ?: (available / 4)).coerceIn(0, available)
        if (budget == 0) return WorldbookProjection()
        val depth = (intOrNull(book.opt("scan_depth")) ?: 2).coerceAtLeast(0)
        val recursive = boolOrNull(book.opt("recursive_scanning")) ?: false

        val entries = mutableListOf<Entry>()
        book.optJSONArray("entries")?.let { array ->
            for (index in 0 until array.length()) {
                val raw = array.optJSONObject(index) ?: continue
                if (boolOrNull(raw.opt("enabled")) == false) continue
                if (CharacterWorldbookSupport.reasons(raw).isNotEmpty()) continue
                val entry = Entry(index, raw)
                if (entry.content.isNotBlank()) entries += entry
            }
        }

        val matched = LinkedHashMap<Int, Entry>()
        do {
            val priorSize = matched.size
            val recursiveText = if (recursive) matched.values.joinToString("\n") { it.content } else ""
            entries.forEach { entry ->
                if (matched.containsKey(entry.index)) return@forEach
                val localDepth = (intOrNull(entry.extensions.opt("scan_depth")) ?: depth).coerceAtLeast(0)
                val scanned = messages.takeLast(localDepth).joinToString("\n") + "\n" + recursiveText
                val caseSensitive = boolOrNull(entry.data.opt("case_sensitive"))
                    ?: boolOrNull(entry.extensions.opt("case_sensitive"))
                    ?: false
                val primary = entry.data.strings("keys").any { matches(it, scanned, caseSensitive) }
                val secondary = boolOrNull(entry.data.opt("selective")) != true ||
                    entry.data.strings("secondary_keys").any { matches(it, scanned, caseSensitive) }
                if (entry.constant || primary && secondary) matched[entry.index] = entry
            }
        } while (recursive && matched.size > priorSize)

        val selected = mutableListOf<Entry>()
        var tokens = 0
        matched.values
            .sortedWith(
                compareByDescending<Entry> { it.constant }
                    .thenByDescending { it.priority }
                    .thenBy { it.order }
                    .thenBy { it.index },
            )
            .forEach { entry ->
                val candidate = (selected + entry).sortedWith(compareBy<Entry> { it.order }.thenBy { it.index })
                val before = candidate.filter { it.before }.joinToString("\n\n") { it.content }
                val after = candidate.filterNot { it.before }.joinToString("\n\n") { it.content }
                val cost = estimateTokens(before).coerceAtLeast(0).toLong() + estimateTokens(after).coerceAtLeast(0)
                if (cost <= budget) {
                    selected += entry
                    tokens = cost.toInt()
                }
            }

        val ordered = selected.sortedWith(compareBy<Entry> { it.order }.thenBy { it.index })
        return WorldbookProjection(
            beforeCharacter = ordered.filter { it.before }.joinToString("\n\n") { it.content },
            afterCharacter = ordered.filterNot { it.before }.joinToString("\n\n") { it.content },
            usedTokens = tokens,
        )
    }

    private fun matches(key: String, text: String, caseSensitive: Boolean): Boolean {
        if (key.isEmpty()) return false
        return text.contains(key, ignoreCase = !caseSensitive)
    }

    private class Entry(val index: Int, val data: JSONObject) {
        val extensions: JSONObject = data.optJSONObject("extensions") ?: JSONObject()
        val content: String = data.text("content")
        val constant: Boolean = boolOrNull(data.opt("constant")) ?: false
        val order: Int = intOrNull(data.opt("insertion_order")) ?: 0
        val priority: Int = intOrNull(data.opt("priority")) ?: 0
        val before: Boolean = when (intOrNull(extensions.opt("position"))) {
            0 -> true
            1 -> false
            else -> data.text("position") == "before_char"
        }
    }
}
