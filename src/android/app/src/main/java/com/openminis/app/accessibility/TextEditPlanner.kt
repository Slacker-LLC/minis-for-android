package com.openminis.app.accessibility

/**
 * [T-eta-text-insert] The text and cursor one real edit leaves behind, computed from the field's
 * own selection.
 *
 * Ported from Eta `agent/accessibility/TextEditPlanner.kt` (Mangi-11/Eta @ c15de97); attribution
 * in THIRD_PARTY_LICENSES.md. Android reports a text field's selection in UTF-16 offsets, and a
 * caller that wants to insert into what is already there has to reconstruct the resulting value
 * itself - there is no append action. The two rules are the point: a field whose text or selection
 * cannot be trusted is refused (the caller is told to write the full value instead), and an
 * impossible selection is only tolerated on an empty field, where the only insertion point is the
 * start.
 */
object TextEditPlanner {

    data class Plan(
        val text: String,
        val cursor: Int,
    )

    /**
     * Whether the field's value and selection may be used to reconstruct an edit at all. A password
     * field is never reconstructed from, and neither is a field that does not hand over its text.
     */
    fun canSafelyReconstruct(
        password: Boolean,
        textAvailable: Boolean,
        textLength: Int,
        selectionStart: Int,
        selectionEnd: Int,
    ): Boolean {
        if (password || !textAvailable || textLength < 0) return false
        if (selectionStart in 0..textLength && selectionEnd in 0..textLength) return true
        // Some empty fields report -1 for a cursor they have not created yet; an empty value still
        // has exactly one insertion point, at 0.
        return textLength == 0 && selectionStart <= 0 && selectionEnd <= 0
    }

    /**
     * The value and cursor after [insertedText] replaces the current selection; null when the
     * selection cannot be trusted and the text is not empty.
     */
    fun insertAtSelection(
        currentText: String,
        insertedText: String,
        selectionStart: Int,
        selectionEnd: Int,
    ): Plan? {
        val selectionIsValid =
            selectionStart in 0..currentText.length && selectionEnd in 0..currentText.length
        if (!selectionIsValid && currentText.isNotEmpty()) return null
        val start = if (selectionIsValid) selectionStart else 0
        val end = if (selectionIsValid) selectionEnd else 0
        val lower = minOf(start, end)
        val upper = maxOf(start, end)
        return Plan(
            text = currentText.substring(0, lower) + insertedText + currentText.substring(upper),
            cursor = lower + insertedText.length,
        )
    }
}
