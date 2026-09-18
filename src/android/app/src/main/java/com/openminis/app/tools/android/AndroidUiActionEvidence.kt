package com.openminis.app.tools.android

import com.openminis.app.accessibility.GatedCallOutcome
import org.json.JSONObject

/**
 * Evidence carried by a GUI action result in the tool JSON.
 *
 * A boolean can only say that an API call returned something. These states
 * separate "the system accepted the action and the effect was observed" from
 * "accepted without effect evidence", "the effect went the other way", "the
 * deadline expired" and "the action was refused". Every action reports one of
 * them; a bare `true` is never a success conclusion on its own.
 */
enum class UiActionEvidence {
    /** The system accepted the action and the observation shows the requested effect. */
    ACCEPTED_WITH_EFFECT,

    /** The system accepted the action; no effect evidence was observed. */
    ACCEPTED_NO_EVIDENCE,

    /** The action took effect in the opposite direction of the request. */
    DIRECTION_MISMATCH,

    /** The deadline expired before any result; the action may still be in flight. */
    TIMED_OUT,

    /** The action was refused, cancelled, or never dispatched. */
    REJECTED,
    ;

    /**
     * True only while the system accepted the action. It proves nothing about the
     * effect; callers read the evidence state for that.
     */
    val acceptedBySystem: Boolean
        get() = this == ACCEPTED_WITH_EFFECT || this == ACCEPTED_NO_EVIDENCE
}

/**
 * Stable machine-readable source of an [UiActionEvidence]. Scroll sources are the
 * ported [ScrollEvidence] verdicts, gesture sources are dispatch outcomes, and the
 * two change states come from re-observing the same ref.
 */
enum class UiActionEvidenceSource {
    MOVED_BY_EVENT,
    MOVED_BY_ANCHOR_MOTION,
    DIRECTION_MISMATCH,
    SCROLL_AXIS_MISMATCH,
    AT_BOUNDARY,
    UNVERIFIED,
    GESTURE_COMPLETED,
    GESTURE_CANCELLED,
    GESTURE_NOT_DISPATCHED,
    GESTURE_TIMEOUT,
    NODE_ACTION_REFUSED,
    UI_FINGERPRINT_CHANGED,
    NO_UI_CHANGE,
    GLOBAL_ACTION_REFUSED,
    MAIN_THREAD_NOT_DISPATCHED,
    MAIN_THREAD_TIMEOUT,
    TEXT_VERIFIED,
    TEXT_UNVERIFIED,
}

/**
 * Whether re-observing the same ref proves that the window content changed. A
 * re-observation that could not be compared (unreadable window set, truncated
 * fingerprint, disconnected service) is [UNKNOWN] and never counts as an effect.
 */
enum class UiChangeObservation {
    CHANGED,
    UNCHANGED,
    UNKNOWN,
}

/** Outcome of one gesture dispatch through the accessibility bridge. */
enum class GestureDispatchOutcome {
    COMPLETED,
    CANCELLED,
    NOT_DISPATCHED,
    TIMED_OUT,
}

/** Evidence state plus the observation it came from. */
data class UiActionEvidenceReport(
    val evidence: UiActionEvidence,
    val source: UiActionEvidenceSource,
) {
    /**
     * Writes the state into the existing tool JSON. Earlier fields are preserved,
     * so callers that only read `success` keep working.
     */
    fun into(json: JSONObject): JSONObject = json
        .put(FIELD_EVIDENCE, evidence.name)
        .put(FIELD_EVIDENCE_SOURCE, source.name)

    companion object {
        const val FIELD_EVIDENCE = "evidence"
        const val FIELD_EVIDENCE_SOURCE = "evidenceSource"
    }
}

/** Projects the ported scroll/gesture contracts onto the tool JSON evidence states. */
object AndroidUiActionEvidence {
    fun ofScroll(evidence: ScrollEvidence): UiActionEvidenceReport = when (evidence) {
        ScrollEvidence.MOVED_BY_EVENT ->
            UiActionEvidenceReport(UiActionEvidence.ACCEPTED_WITH_EFFECT, UiActionEvidenceSource.MOVED_BY_EVENT)

        ScrollEvidence.MOVED_BY_ANCHOR_MOTION ->
            UiActionEvidenceReport(UiActionEvidence.ACCEPTED_WITH_EFFECT, UiActionEvidenceSource.MOVED_BY_ANCHOR_MOTION)

        ScrollEvidence.DIRECTION_MISMATCH ->
            UiActionEvidenceReport(UiActionEvidence.DIRECTION_MISMATCH, UiActionEvidenceSource.DIRECTION_MISMATCH)

        ScrollEvidence.AT_BOUNDARY ->
            UiActionEvidenceReport(UiActionEvidence.ACCEPTED_NO_EVIDENCE, UiActionEvidenceSource.AT_BOUNDARY)

        ScrollEvidence.UNVERIFIED ->
            UiActionEvidenceReport(UiActionEvidence.ACCEPTED_NO_EVIDENCE, UiActionEvidenceSource.UNVERIFIED)
    }

    fun ofGesture(outcome: GestureDispatchOutcome): UiActionEvidenceReport = when (outcome) {
        GestureDispatchOutcome.COMPLETED ->
            UiActionEvidenceReport(UiActionEvidence.ACCEPTED_NO_EVIDENCE, UiActionEvidenceSource.GESTURE_COMPLETED)

        GestureDispatchOutcome.CANCELLED ->
            UiActionEvidenceReport(UiActionEvidence.REJECTED, UiActionEvidenceSource.GESTURE_CANCELLED)

        GestureDispatchOutcome.NOT_DISPATCHED ->
            UiActionEvidenceReport(UiActionEvidence.REJECTED, UiActionEvidenceSource.GESTURE_NOT_DISPATCHED)

        GestureDispatchOutcome.TIMED_OUT ->
            UiActionEvidenceReport(UiActionEvidence.TIMED_OUT, UiActionEvidenceSource.GESTURE_TIMEOUT)
    }

    fun ofRefusedNodeAction(): UiActionEvidenceReport =
        UiActionEvidenceReport(UiActionEvidence.REJECTED, UiActionEvidenceSource.NODE_ACTION_REFUSED)

    /**
     * Evidence for a click/long-press after re-observation. [fallback] carries the
     * gesture state when the node action had to fall back to a coordinate gesture,
     * so a timeout stays a timeout instead of becoming a plain refusal.
     */
    fun ofClick(
        accepted: Boolean,
        change: UiChangeObservation,
        fallback: UiActionEvidenceReport?,
    ): UiActionEvidenceReport = when {
        !accepted -> fallback ?: ofRefusedNodeAction()
        change == UiChangeObservation.CHANGED -> UiActionEvidenceReport(
            UiActionEvidence.ACCEPTED_WITH_EFFECT,
            UiActionEvidenceSource.UI_FINGERPRINT_CHANGED,
        )
        else -> UiActionEvidenceReport(UiActionEvidence.ACCEPTED_NO_EVIDENCE, UiActionEvidenceSource.NO_UI_CHANGE)
    }

    /**
     * Evidence for a `back`/`home`-style global action. The action itself carries no
     * effect observation, so only a foreground change that was actually read may be
     * reported as an effect, and a call that never started stays a refusal.
     */
    fun ofGlobalAction(outcome: GatedCallOutcome, change: UiChangeObservation): UiActionEvidenceReport = when (outcome) {
        GatedCallOutcome.COMPLETED_ACCEPTED ->
            if (change == UiChangeObservation.CHANGED) {
                UiActionEvidenceReport(UiActionEvidence.ACCEPTED_WITH_EFFECT, UiActionEvidenceSource.UI_FINGERPRINT_CHANGED)
            } else {
                UiActionEvidenceReport(UiActionEvidence.ACCEPTED_NO_EVIDENCE, UiActionEvidenceSource.NO_UI_CHANGE)
            }

        GatedCallOutcome.COMPLETED_REFUSED ->
            UiActionEvidenceReport(UiActionEvidence.REJECTED, UiActionEvidenceSource.GLOBAL_ACTION_REFUSED)

        GatedCallOutcome.NOT_DISPATCHED ->
            UiActionEvidenceReport(UiActionEvidence.REJECTED, UiActionEvidenceSource.MAIN_THREAD_NOT_DISPATCHED)

        GatedCallOutcome.OUTCOME_UNKNOWN ->
            UiActionEvidenceReport(UiActionEvidence.TIMED_OUT, UiActionEvidenceSource.MAIN_THREAD_TIMEOUT)
    }

    /**
     * Evidence for `set_text`. [verified] is null when the field itself forbids a
     * read-back (password) and false when the refreshed node does not carry the
     * requested text; neither may be reported as an observed effect.
     */
    fun ofTextInput(accepted: Boolean, verified: Boolean?): UiActionEvidenceReport = when {
        !accepted -> ofRefusedNodeAction()
        verified == true -> UiActionEvidenceReport(UiActionEvidence.ACCEPTED_WITH_EFFECT, UiActionEvidenceSource.TEXT_VERIFIED)
        else -> UiActionEvidenceReport(UiActionEvidence.ACCEPTED_NO_EVIDENCE, UiActionEvidenceSource.TEXT_UNVERIFIED)
    }
}
