package com.openminis.app.tools.android

/**
 * Pure decision for "may this ref still be used" once the screen may have changed.
 *
 * The earlier rule was: any change of the whole-screen fingerprint is STALE. That made the
 * "node moved, but its unique identity is still on screen" fallback unreachable (a moved
 * node always changes the fingerprint), and made a ticking clock or a banner invalidate
 * every ref. The order here is:
 *
 *  1. an expired / foreign / unknown ref is refused outright;
 *  2. the node at the observed path still carries the observed identity, and either the
 *     screen is provably unchanged or that identity is strong (text, description or
 *     uniqueId) -> use it;
 *  3. otherwise a single, provably unique identity match elsewhere in the tree -> use it;
 *  4. otherwise refuse.
 *
 * Nothing here weakens the identity itself: windowId, package, class, password flag and
 * text/description must all match, exactly as before. What is dropped is the requirement
 * that the *rest* of the screen is byte-identical.
 */
object UiRefResolutionPolicy {
    const val STALE_UI_REF = "STALE_UI_REF"
    const val UI_REF_NOT_FOUND = "UI_REF_NOT_FOUND"
    const val UI_SNAPSHOT_TRUNCATED = "UI_SNAPSHOT_TRUNCATED"

    enum class Basis { PATH, UNIQUE_IDENTITY }

    sealed interface Decision {
        data class Use(val basis: Basis) : Decision
        data class Refuse(val code: String) : Decision
    }

    /**
     * @param candidateCount evaluated lazily, only when the path check did not settle it; it
     * must count identity matches in the current tree.
     * @param pathBackedByPosition the node at the path also has exactly the observed bounds. It
     * lets a node with only a weak identity (an icon button with a viewId but no text) keep
     * working after unrelated parts of the screen changed; it never enables the unique fallback.
     * @param trustPathUnderTruncation whether a path+strong-identity match may be used when
     * the scan was cut short. A cut-off scan can never prove uniqueness, so the unique
     * fallback additionally requires [hasUniqueId] in that case.
     */
    fun decide(
        freshness: UiGenerationFence.Freshness,
        pathIdentityMatches: Boolean,
        identityStrong: Boolean,
        hasUniqueId: Boolean,
        snapshotTruncated: Boolean,
        trustPathUnderTruncation: Boolean,
        pathBackedByPosition: Boolean = false,
        candidateCount: () -> Int,
    ): Decision {
        when (freshness) {
            UiGenerationFence.Freshness.STALE -> return Decision.Refuse(STALE_UI_REF)
            UiGenerationFence.Freshness.REF_NOT_FOUND -> return Decision.Refuse(UI_REF_NOT_FOUND)
            else -> Unit
        }
        if (snapshotTruncated && !trustPathUnderTruncation) return Decision.Refuse(UI_SNAPSHOT_TRUNCATED)
        val unchanged = freshness == UiGenerationFence.Freshness.FRESH
        if (pathIdentityMatches && (unchanged || identityStrong || pathBackedByPosition)) return Decision.Use(Basis.PATH)
        // Unique-identity fallback: a weak identity can never be proven unique, and a cut-off
        // scan cannot prove uniqueness without a platform uniqueId.
        if (!identityStrong) return Decision.Refuse(STALE_UI_REF)
        if (snapshotTruncated && !hasUniqueId) {
            return Decision.Refuse(if (trustPathUnderTruncation) STALE_UI_REF else UI_SNAPSHOT_TRUNCATED)
        }
        return if (candidateCount() == 1) Decision.Use(Basis.UNIQUE_IDENTITY) else Decision.Refuse(STALE_UI_REF)
    }
}
