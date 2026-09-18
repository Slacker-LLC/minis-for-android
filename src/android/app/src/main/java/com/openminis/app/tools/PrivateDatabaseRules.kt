package com.openminis.app.tools

/**
 * [T-eta-xposed-groups] The rules that guard a snapshot of another app's database.
 *
 * Ported from Eta `agent/tool/AgentPrivateDatabaseTools.kt` (Mangi-11/Eta @ c15de97); attribution
 * in THIRD_PARTY_LICENSES.md. Eta enforces them inside one shell script; this project's privileged
 * surface takes arguments, so the same decisions are made here, as values, before any command runs:
 * a snapshot is only taken from a real file of a size the cap allows, a schema that does not carry
 * the columns the query needs is refused instead of guessed, and nothing a caller passes can change
 * the path, the table or the columns.
 */
object PrivateDatabaseRules {
    const val DEFAULT_LIMIT = 20
    const val MAX_LIMIT = 50

    /** A single text field is bounded before it reaches the model. */
    const val MAX_FIELD_CHARS = 4_000

    fun clampLimit(requested: Int?): Int = (requested ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

    /** True when the file is larger than the snapshot cap. */
    fun exceedsSizeCap(sizeBytes: Long, maxBytes: Long): Boolean = sizeBytes > maxBytes

    /** `readlink` printing a path means the source is a link, and a link is not copied. */
    fun isSymlink(readlinkStdout: String): Boolean = readlinkStdout.isNotBlank()

    /** A table is only queried when it carries everything the query needs. */
    fun hasColumns(available: Set<String>, required: Set<String>): Boolean =
        available.containsAll(required)

    /** Escapes a keyword so it matches literally inside a LIKE pattern. */
    fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
}
