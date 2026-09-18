package com.openminis.app.roleplay

import org.json.JSONArray
import org.json.JSONObject

/** [T-eta-character-cards] A world book as the editor works on it. */
data class CharacterBookDraft(
    val name: String = "",
    val scanDepth: Int? = null,
    val recursiveScanning: Boolean? = null,
    val tokenBudget: Int? = null,
    val entries: List<CharacterBookEntryDraft> = emptyList(),
    val raw: JSONObject = JSONObject(),
)

/** [T-eta-character-cards] One lore entry as the editor works on it. */
data class CharacterBookEntryDraft(
    val name: String = "",
    val content: String = "",
    val keys: List<String> = emptyList(),
    val secondaryKeys: List<String> = emptyList(),
    val enabled: Boolean = true,
    val constant: Boolean = false,
    val selective: Boolean = false,
    val position: String = "after_char",
    val insertionOrder: Int = 0,
    val raw: JSONObject = JSONObject(),
)

/**
 * [T-eta-character-cards] Reading a card's world book into an editable draft, and writing it back.
 *
 * Ported from Eta `agent/roleplay/CharacterWorldbookDraft.kt` (Mangi-11/Eta @ c15de97); attribution
 * in THIRD_PARTY_LICENSES.md. Eta's contract is the interesting part and is kept exactly: an edit
 * touches only the fields that actually changed, and every field the editor does not model — regex
 * extensions, probability gates, vendor keys, anything — survives the round trip byte for byte. A
 * lore library is somebody's work, and a "simple" reset of unknown fields would quietly destroy it.
 *
 * The position of an entry lives in two places in the wild (the `position` string and the
 * `extensions.position` number), so the draft reads whichever is present and writes back in the same
 * shape it found, rather than normalising a card that was already valid.
 */
object CharacterWorldbookDraftCodec {

    fun read(card: CharacterCard): CharacterBookDraft {
        val book = card.characterBook ?: return CharacterBookDraft()
        val entries = mutableListOf<CharacterBookEntryDraft>()
        val array = book.optJSONArray("entries")
        if (array != null) {
            for (index in 0 until array.length()) {
                val entry = array.optJSONObject(index) ?: continue
                val extensionPosition = intOrNull(entry.optJSONObject("extensions")?.opt("position"))
                entries += CharacterBookEntryDraft(
                    name = entry.text("name").ifEmpty { entry.text("comment") },
                    content = entry.text("content"),
                    keys = entry.strings("keys"),
                    secondaryKeys = entry.strings("secondary_keys"),
                    enabled = boolOrNull(entry.opt("enabled")) ?: true,
                    constant = boolOrNull(entry.opt("constant")) ?: false,
                    selective = boolOrNull(entry.opt("selective")) ?: false,
                    position = when (extensionPosition) {
                        0 -> "before_char"
                        1 -> "after_char"
                        else -> entry.text("position").ifEmpty { "after_char" }
                    },
                    insertionOrder = intOrNull(entry.opt("insertion_order")) ?: index,
                    raw = entry,
                )
            }
        }
        return CharacterBookDraft(
            name = book.text("name"),
            scanDepth = intOrNull(book.opt("scan_depth")),
            recursiveScanning = boolOrNull(book.opt("recursive_scanning")),
            tokenBudget = intOrNull(book.opt("token_budget")),
            entries = entries,
            raw = book,
        )
    }

    fun write(card: CharacterCard, draft: CharacterBookDraft): CharacterCard {
        require(draft.scanDepth == null || draft.scanDepth >= 0) { "扫描深度不能为负数" }
        require(draft.tokenBudget == null || draft.tokenBudget >= 0) { "世界书预算不能为负数" }
        val book = draft.raw.deepCopy()
        if (draft.name != draft.raw.text("name")) book.put("name", draft.name)
        if (draft.scanDepth != intOrNull(draft.raw.opt("scan_depth"))) {
            if (draft.scanDepth == null) book.remove("scan_depth") else book.put("scan_depth", draft.scanDepth)
        }
        if (draft.tokenBudget != intOrNull(draft.raw.opt("token_budget"))) {
            if (draft.tokenBudget == null) book.remove("token_budget") else book.put("token_budget", draft.tokenBudget)
        }
        if (draft.recursiveScanning != boolOrNull(draft.raw.opt("recursive_scanning"))) {
            if (draft.recursiveScanning == null) {
                book.remove("recursive_scanning")
            } else {
                book.put("recursive_scanning", draft.recursiveScanning)
            }
        }
        if (draft.raw.length() == 0) book.put("extensions", JSONObject())
        if (draft.raw.length() == 0 || draft.raw.has("entries") || draft.entries.isNotEmpty()) {
            book.put("entries", JSONArray().also { array ->
                draft.entries.forEach { entry -> array.put(writeEntry(entry)) }
            })
        }
        val data = card.data.deepCopy()
        data.put("character_book", book)
        val root = card.raw.deepCopy()
        root.put("data", data)
        return CharacterCard(root)
    }

    private fun writeEntry(entry: CharacterBookEntryDraft): JSONObject {
        require(entry.position in setOf("before_char", "after_char") || entry.position == entry.raw.text("position")) {
            "不支持的世界书插入位置"
        }
        val fresh = entry.raw.length() == 0
        val raw = entry.raw.deepCopy()
        val originalName = entry.raw.text("name").ifEmpty { entry.raw.text("comment") }
        if (entry.name != originalName) {
            raw.put("name", entry.name)
            if (raw.has("comment")) raw.put("comment", entry.name)
        }
        if (fresh || entry.content != entry.raw.text("content")) raw.put("content", entry.content)
        if (fresh || entry.keys != entry.raw.strings("keys")) raw.put("keys", stringArray(entry.keys))
        if (entry.secondaryKeys != entry.raw.strings("secondary_keys")) {
            raw.put("secondary_keys", stringArray(entry.secondaryKeys))
        }
        if (fresh || entry.enabled != (boolOrNull(entry.raw.opt("enabled")) ?: true)) {
            raw.put("enabled", entry.enabled)
        }
        if (entry.constant != (boolOrNull(entry.raw.opt("constant")) ?: false)) raw.put("constant", entry.constant)
        if (entry.selective != (boolOrNull(entry.raw.opt("selective")) ?: false)) raw.put("selective", entry.selective)
        val originalOrder = intOrNull(entry.raw.opt("insertion_order"))
        if (fresh || (originalOrder != null && originalOrder != entry.insertionOrder)) {
            raw.put("insertion_order", entry.insertionOrder)
        }
        val extensions = entry.raw.optJSONObject("extensions")?.deepCopy() ?: JSONObject()
        val storedPosition = intOrNull(extensions.opt("position"))
        val originalPosition = when (storedPosition) {
            0 -> "before_char"
            1 -> "after_char"
            else -> entry.raw.text("position").ifEmpty { "after_char" }
        }
        if (fresh || entry.position != originalPosition) {
            raw.put("position", entry.position)
            if (extensions.has("position")) {
                extensions.put("position", if (entry.position == "before_char") 0 else 1)
            }
        }
        if (fresh || raw.has("extensions")) raw.put("extensions", extensions)
        return raw
    }

    private fun stringArray(values: List<String>): JSONArray =
        JSONArray().also { array -> values.forEach(array::put) }
}
