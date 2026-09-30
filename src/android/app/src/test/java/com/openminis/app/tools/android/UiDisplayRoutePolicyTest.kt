package com.openminis.app.tools.android

import org.junit.Assert.assertEquals
import org.junit.Test

class UiDisplayRoutePolicyTest {
    @Test fun physicalDisplayAlwaysUsesAccessibility() {
        assertEquals(UiDisplayRoutePolicy.Route.ACCESSIBILITY, UiDisplayRoutePolicy.route(0, 9))
    }

    @Test fun onlyTheActiveVirtualDisplayUsesVirtualBackend() {
        assertEquals(UiDisplayRoutePolicy.Route.VIRTUAL_SCREEN, UiDisplayRoutePolicy.route(9, 9))
    }

    @Test fun unknownAndInactiveDisplayIdsFailClosed() {
        assertEquals(UiDisplayRoutePolicy.Route.UNKNOWN, UiDisplayRoutePolicy.route(8, 9))
        assertEquals(UiDisplayRoutePolicy.Route.UNKNOWN, UiDisplayRoutePolicy.route(9, null))
        assertEquals(UiDisplayRoutePolicy.Route.UNKNOWN, UiDisplayRoutePolicy.route(-1, 9))
    }

    @Test fun malformedDisplayIdsNeverFallBackToPhysicalDisplay() {
        assertEquals(0, UiDisplayRoutePolicy.parseDisplayId(false, null))
        assertEquals(7, UiDisplayRoutePolicy.parseDisplayId(true, 7L))
        assertEquals(null, UiDisplayRoutePolicy.parseDisplayId(true, "7"))
        assertEquals(null, UiDisplayRoutePolicy.parseDisplayId(true, 7.5))
        assertEquals(null, UiDisplayRoutePolicy.parseDisplayId(true, Double.NaN))
    }
}
