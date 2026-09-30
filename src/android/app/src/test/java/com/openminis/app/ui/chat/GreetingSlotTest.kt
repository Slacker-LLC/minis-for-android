package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class GreetingSlotTest {
    @Test fun slotBoundaries() {
        assertEquals(GreetingSlot.MORNING, greetingSlotFor(0))
        assertEquals(GreetingSlot.MORNING, greetingSlotFor(11))
        assertEquals(GreetingSlot.AFTERNOON, greetingSlotFor(12))
        assertEquals(GreetingSlot.AFTERNOON, greetingSlotFor(17))
        assertEquals(GreetingSlot.EVENING, greetingSlotFor(18))
        assertEquals(GreetingSlot.EVENING, greetingSlotFor(23))
    }
}
