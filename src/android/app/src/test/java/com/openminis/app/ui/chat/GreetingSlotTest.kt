package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class GreetingSlotTest {
    @Test fun slotBoundaries() {
        assertEquals(GreetingSlot.DAWN, greetingSlotFor(0))
        assertEquals(GreetingSlot.DAWN, greetingSlotFor(4))
        assertEquals(GreetingSlot.MORNING, greetingSlotFor(5))
        assertEquals(GreetingSlot.MORNING, greetingSlotFor(10))
        assertEquals(GreetingSlot.NOON, greetingSlotFor(11))
        assertEquals(GreetingSlot.NOON, greetingSlotFor(13))
        assertEquals(GreetingSlot.AFTERNOON, greetingSlotFor(14))
        assertEquals(GreetingSlot.AFTERNOON, greetingSlotFor(17))
        assertEquals(GreetingSlot.EVENING, greetingSlotFor(18))
        assertEquals(GreetingSlot.EVENING, greetingSlotFor(22))
        assertEquals(GreetingSlot.NIGHT, greetingSlotFor(23))
    }

    @Test fun everyHourHasASlot() {
        for (h in 0..23) greetingSlotFor(h)
    }

    @Test fun eachSlotHasTwoDifferentWordings() {
        for (slot in GreetingSlot.entries) {
            assertNotEquals(greetingRes(slot, 0), greetingRes(slot, 1))
            assertEquals(greetingRes(slot, 0), greetingRes(slot, 2))
        }
    }

    @Test fun negativeVariantsStillPickAWording() {
        greetingRes(GreetingSlot.MORNING, -3)
        promptRes(-7)
    }

    @Test fun promptVariantsRotate() {
        val seen = (0 until 12).map { promptRes(it) }.toSet()
        assertEquals(6, seen.size)
    }
}
