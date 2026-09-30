package com.openminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageBarSharesTest {
    @Test fun sharesAddUpToOne() {
        val shares = storageBarShares(shell = 2_400, database = 38, sessions = 210)
        assertEquals(1f, shares.sum(), 0.0001f)
        assertEquals(2_400f / 2_648f, shares[0], 0.0001f)
    }

    @Test fun nothingMeasuredYetGivesAnEmptyTrackNotADivisionByZero() {
        assertEquals(listOf(0f, 0f, 0f), storageBarShares(0, 0, 0))
    }

    @Test fun negativeInputsAreTreatedAsZero() {
        val shares = storageBarShares(shell = -5, database = 10, sessions = 10)
        assertEquals(listOf(0f, 0.5f, 0.5f), shares)
    }
}
