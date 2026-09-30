package com.openminis.app.tools.android

/** Display routing is explicit: the physical screen stays on Accessibility, and only the active VScreen id is accepted. */
object UiDisplayRoutePolicy {
    enum class Route { ACCESSIBILITY, VIRTUAL_SCREEN, UNKNOWN }

    fun parseDisplayId(provided: Boolean, raw: Any?): Int? {
        if (!provided) return 0
        val number = raw as? Number ?: return null
        val value = number.toDouble()
        if (!value.isFinite() || value < 0.0 || value > Int.MAX_VALUE || value % 1.0 != 0.0) return null
        return value.toInt()
    }

    fun route(requestedDisplayId: Int, activeVirtualDisplayId: Int?): Route = when {
        requestedDisplayId == 0 -> Route.ACCESSIBILITY
        requestedDisplayId > 0 && requestedDisplayId == activeVirtualDisplayId -> Route.VIRTUAL_SCREEN
        else -> Route.UNKNOWN
    }
}
