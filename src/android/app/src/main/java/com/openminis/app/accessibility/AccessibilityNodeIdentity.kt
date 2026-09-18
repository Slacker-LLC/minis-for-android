package com.openminis.app.accessibility

/** JVM-verifiable node identity; the view id alone never identifies a list item. */
data class AccessibilityNodeIdentity(
    val uniqueId: String,
    val windowId: Int,
    val packageName: String,
    val className: String,
    val viewId: String,
    val text: String,
    val description: String,
    val password: Boolean,
) {
    val strong: Boolean
        get() = uniqueId.isNotBlank() || text.isNotBlank() || description.isNotBlank()

    fun matches(refreshed: AccessibilityNodeIdentity): Boolean {
        if (windowId != refreshed.windowId) return false
        if (packageName != refreshed.packageName) return false
        if (className != refreshed.className) return false
        if (password != refreshed.password) return false
        if (uniqueId != refreshed.uniqueId) return false
        if (viewId.isNotBlank() && viewId != refreshed.viewId) return false
        // A uniqueId only proves it is still the same virtual node; it does not
        // prove the node still carries the action semantics that were observed.
        if (text != refreshed.text) return false
        if (description != refreshed.description) return false
        return true
    }
}

/**
 * After a window changes, only an identity that is stable and provably unique
 * inside the observation may keep being used. A truncated snapshot cannot prove
 * that a text/description fingerprint is unique in the rest of the window.
 */
object AccessibilityIdentityFreshnessPolicy {
    fun canBypassContentChange(
        hasUniqueId: Boolean,
        snapshotTruncated: Boolean,
        identityMatchCount: Int,
    ): Boolean = identityMatchCount == 1 && (hasUniqueId || !snapshotTruncated)
}
