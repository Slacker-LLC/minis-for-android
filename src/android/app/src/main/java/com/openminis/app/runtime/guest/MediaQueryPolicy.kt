package com.openminis.app.runtime.guest

/**
 * [T-eta-media-search] Bounds and filter building shared by the MediaStore list queries.
 *
 * Ported from Eta `agent/tool/AgentPersonalDataTools.kt` (`search_media`, `search_audio`) and
 * the media entries in `agent/model/AgentDeviceToolCatalog.kt` (Mangi-11/Eta @ c15de97);
 * attribution in THIRD_PARTY_LICENSES.md. Eta filters by file name and album path and returns
 * metadata plus a content URI without reading the bytes; these rules are the same, expressed
 * once for the photo/video/audio queries this app already owns.
 */
object MediaQueryPolicy {

    const val DEFAULT_LIMIT = 20
    const val MAX_LIMIT = 100

    /**
     * Media kinds the list query understands. `all` covers photo + video + audio; documents
     * stay out of it because mixing every non-media file into a listing helps nobody —
     * `--type file` asks for them explicitly.
     */
    val TYPES = listOf("photo", "video", "audio", "file", "all")

    fun clampLimit(raw: Int?): Int = (raw ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

    /** Null when the caller asked for a kind that does not exist. */
    fun type(raw: String?): String? {
        // An absent OR blank value means "everything", the same default the CLI has always had.
        val value = raw?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "all"
        return value.takeIf { it in TYPES }
    }

    /**
     * A file-name filter as a SQL clause plus its argument, or null when no query was given.
     * Wildcards inside the query are escaped so a search for "50%" stays literal.
     */
    fun nameFilter(query: String?, column: String = "display_name"): Pair<String, String>? {
        val needle = query?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return "$column LIKE ? ESCAPE '\\'" to "%${escapeLike(needle)}%"
    }

    fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /**
     * Eta's recordings search is the audio query narrowed to a recording directory. The same
     * clause is used here so "recordings" means the same thing in both apps.
     */
    const val RECORDINGS_PATH_CLAUSE = "relative_path LIKE '%Record%'"

    /**
     * A query matched against SEVERAL columns (Eta searches a document's name and its path).
     * The escaped pattern is repeated once per column, because the caller binds one argument
     * per placeholder.
     */
    fun anyColumnFilter(query: String?, columns: List<String>): Pair<String, List<String>>? {
        val needle = query?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (columns.isEmpty()) return null
        val pattern = "%${escapeLike(needle)}%"
        val clause = columns.joinToString(" OR ") { column -> "$column LIKE ? ESCAPE '\\'" }
        return "($clause)" to List(columns.size) { pattern }
    }
}
