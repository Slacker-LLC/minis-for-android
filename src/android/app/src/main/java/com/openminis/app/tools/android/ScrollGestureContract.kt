package com.openminis.app.tools.android

import kotlin.math.roundToInt

enum class ScrollAxis {
    HORIZONTAL,
    VERTICAL,
}

/** A scroll direction names where new content should appear, not where the finger moves. */
enum class ScrollDirection(
    val axis: ScrollAxis,
    val scrollDeltaSign: Int,
) {
    UP(ScrollAxis.VERTICAL, -1),
    DOWN(ScrollAxis.VERTICAL, 1),
    LEFT(ScrollAxis.HORIZONTAL, -1),
    RIGHT(ScrollAxis.HORIZONTAL, 1),
    ;

    /**
     * Drag gesture that scrolls this direction inside [left]..[right] / [top]..[bottom].
     * Coordinates are passed as plain ints so the contract stays JVM-testable.
     */
    fun gestureWithin(left: Int, top: Int, right: Int, bottom: Int): ScrollGesture? {
        if (right <= left || bottom <= top) return null

        val axisStart = if (axis == ScrollAxis.VERTICAL) top else left
        val axisEndExclusive = if (axis == ScrollAxis.VERTICAL) bottom else right
        if (axisEndExclusive - axisStart < MIN_GESTURE_SPAN_PX) return null

        val near = pointOnAxis(axisStart, axisEndExclusive, NEAR_FRACTION)
        val far = pointOnAxis(axisStart, axisEndExclusive, FAR_FRACTION)
        val perpendicular = if (axis == ScrollAxis.VERTICAL) {
            midpoint(left, right)
        } else {
            midpoint(top, bottom)
        }

        val startAxis = if (scrollDeltaSign > 0) far else near
        val endAxis = if (scrollDeltaSign > 0) near else far
        return if (axis == ScrollAxis.VERTICAL) {
            ScrollGesture(
                start = ScrollPoint(perpendicular, startAxis),
                end = ScrollPoint(perpendicular, endAxis),
            )
        } else {
            ScrollGesture(
                start = ScrollPoint(startAxis, perpendicular),
                end = ScrollPoint(endAxis, perpendicular),
            )
        }
    }

    companion object {
        fun parse(value: String): ScrollDirection? =
            entries.firstOrNull { direction -> direction.name.equals(value.trim(), ignoreCase = true) }

        private const val MIN_GESTURE_SPAN_PX = 2
        private const val NEAR_FRACTION = 0.2f
        private const val FAR_FRACTION = 0.8f

        private fun pointOnAxis(start: Int, endExclusive: Int, fraction: Float): Int {
            val endInclusive = endExclusive - 1
            return (start + (endInclusive - start) * fraction)
                .roundToInt()
                .coerceIn(start, endInclusive)
        }

        private fun midpoint(start: Int, endExclusive: Int): Int =
            (start + (endExclusive - start) / 2).coerceAtMost(endExclusive - 1)
    }
}

data class ScrollPoint(
    val x: Int,
    val y: Int,
)

data class ScrollGesture(
    val start: ScrollPoint,
    val end: ScrollPoint,
)
