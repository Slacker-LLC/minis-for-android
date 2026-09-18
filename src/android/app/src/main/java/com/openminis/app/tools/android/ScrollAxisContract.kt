package com.openminis.app.tools.android

/** Refuses a gesture fallback when the node explicitly exposes the other scroll axis only. */
object ScrollAxisContract {
    fun exposesOnlyOppositeAxis(
        requestedAxis: ScrollAxis,
        hasVerticalActions: Boolean,
        hasHorizontalActions: Boolean,
    ): Boolean = when (requestedAxis) {
        ScrollAxis.VERTICAL -> hasHorizontalActions && !hasVerticalActions
        ScrollAxis.HORIZONTAL -> hasVerticalActions && !hasHorizontalActions
    }

    /** FORWARD/BACKWARD carry no axis; only vertical evidence without horizontal evidence may mean vertical. */
    fun mayTreatLegacyActionsAsVertical(
        requestedAxis: ScrollAxis,
        hasVerticalActions: Boolean,
        hasHorizontalActions: Boolean,
    ): Boolean =
        requestedAxis == ScrollAxis.VERTICAL &&
            hasVerticalActions &&
            !hasHorizontalActions
}
