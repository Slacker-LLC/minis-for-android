package com.openminis.app.accessibility

/**
 * [T-eta-text-insert] The clipboard one paste borrows, and when it may be handed back.
 *
 * Ported from Eta `AgentAccessibilityService.restoreClipboardIfStillOwned` and `pasteText`
 * (agent/accessibility/AgentAccessibilityService.kt @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. Two things matter and both are easy to get wrong: the temporary clip is
 * labelled, so the restore can tell whether the clipboard still holds what this app put there - a
 * user who copied something else in the meantime must not have it overwritten by an old snapshot -
 * and the clip is marked sensitive, so the text does not show up in clipboard previews or get
 * handed to whatever else reads the clipboard.
 */
object ClipboardRestorePolicy {

    /** The label prefix of a clip this app wrote for one paste. */
    const val TEMPORARY_PREFIX = "Minis Android input"

    /** The key that keeps a temporary clip out of previews and out of other apps' hands. */
    const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

    fun temporaryLabel(sequence: Long): String = TEMPORARY_PREFIX + ":" + sequence

    /**
     * True when the clipboard still holds our temporary clip. A different label - the user copied
     * something, or another app did - means the clipboard is no longer ours to restore.
     */
    fun shouldRestore(currentLabel: String?, temporaryLabel: String): Boolean =
        currentLabel != null && currentLabel == temporaryLabel
}
