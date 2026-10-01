package com.openminis.app.tools.android

import com.openminis.app.tools.android.UiGenerationFence.Freshness
import com.openminis.app.tools.android.UiRefResolutionPolicy.Basis
import com.openminis.app.tools.android.UiRefResolutionPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class UiRefResolutionPolicyTest {
    private fun decide(
        freshness: Freshness = Freshness.CONTENT_CHANGED,
        pathMatches: Boolean = true,
        strong: Boolean = true,
        uniqueId: Boolean = false,
        truncated: Boolean = false,
        trustPath: Boolean = false,
        candidates: Int = 0,
        samePosition: Boolean = false,
        onCount: () -> Unit = {},
    ) = UiRefResolutionPolicy.decide(freshness, pathMatches, strong, uniqueId, truncated, trustPath, samePosition) {
        onCount()
        candidates
    }

    // ---- positive: the behaviour this policy exists to enable ----

    @Test fun unchangedScreenWithMatchingPathIsUsedEvenForAWeakIdentity() {
        assertEquals(Decision.Use(Basis.PATH), decide(Freshness.FRESH, strong = false))
    }

    @Test fun changedScreenKeepsAStrongIdentityAtItsPath() {
        // A banner or a clock changed the fingerprint; the target itself is still the same node.
        assertEquals(Decision.Use(Basis.PATH), decide(Freshness.CONTENT_CHANGED, pathMatches = true, strong = true))
    }

    @Test fun movedNodeIsFoundByItsUniqueIdentity() {
        // This branch was unreachable before: a moved node always changed the fingerprint first.
        assertEquals(
            Decision.Use(Basis.UNIQUE_IDENTITY),
            decide(Freshness.CONTENT_CHANGED, pathMatches = false, strong = true, candidates = 1),
        )
    }

    @Test fun truncatedScanMayStillUsePathPlusStrongIdentityWhenTheCallerTrustsIt() {
        assertEquals(Decision.Use(Basis.PATH), decide(truncated = true, trustPath = true))
    }

    // ---- negative: every one of these must refuse ----

    @Test fun expiredOrForeignRefIsRefusedWithoutLookingAtTheScreen() {
        var counted = false
        assertEquals(
            Decision.Refuse("STALE_UI_REF"),
            decide(Freshness.STALE, onCount = { counted = true }),
        )
        assertFalse(counted)
        assertEquals(Decision.Refuse("UI_REF_NOT_FOUND"), decide(Freshness.REF_NOT_FOUND))
    }

    @Test fun changedScreenWithAWeakIdentityAtThePathIsRefused() {
        // No text, description or uniqueId: the path alone proves nothing once the screen moved.
        assertEquals(
            Decision.Refuse("STALE_UI_REF"),
            decide(Freshness.CONTENT_CHANGED, pathMatches = true, strong = false, candidates = 1),
        )
    }

    @Test fun differentNodeAtThePathAndNoUniqueMatchIsRefused() {
        assertEquals(Decision.Refuse("STALE_UI_REF"), decide(pathMatches = false, candidates = 0))
    }

    @Test fun duplicatedIdentityIsRefusedInsteadOfGuessing() {
        assertEquals(Decision.Refuse("STALE_UI_REF"), decide(pathMatches = false, candidates = 2))
    }

    @Test fun truncatedScanWithoutTrustRefusesEvenWhenThePathMatches() {
        assertEquals(Decision.Refuse("UI_SNAPSHOT_TRUNCATED"), decide(truncated = true, trustPath = false))
    }

    @Test fun truncatedScanCannotProveUniquenessWithoutAPlatformUniqueId() {
        assertEquals(
            Decision.Refuse("STALE_UI_REF"),
            decide(pathMatches = false, truncated = true, trustPath = true, uniqueId = false, candidates = 1),
        )
        assertEquals(
            Decision.Use(Basis.UNIQUE_IDENTITY),
            decide(pathMatches = false, truncated = true, trustPath = true, uniqueId = true, candidates = 1),
        )
    }

    @Test fun weakIdentityAtTheSameBoundsKeepsWorkingAfterTheScreenChanged() {
        assertEquals(Decision.Use(Basis.PATH), decide(strong = false, samePosition = true))
    }

    @Test fun samePositionNeverEnablesTheUniqueFallbackForAWeakIdentity() {
        assertEquals(
            Decision.Refuse("STALE_UI_REF"),
            decide(pathMatches = false, strong = false, samePosition = true, candidates = 1),
        )
    }

    @Test fun samePositionDoesNotOverrideAnIdentityMismatchAtThePath() {
        assertEquals(Decision.Refuse("STALE_UI_REF"), decide(pathMatches = false, strong = true, samePosition = true, candidates = 0))
    }

    @Test fun samePositionDoesNotOverrideTheStrictTruncationRule() {
        assertEquals(Decision.Refuse("UI_SNAPSHOT_TRUNCATED"), decide(truncated = true, trustPath = false, samePosition = true))
    }
}
