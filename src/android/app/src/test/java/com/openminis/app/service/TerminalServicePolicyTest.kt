package com.openminis.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Issue #183: the terminal service is resident only for a live process. */
class TerminalServicePolicyTest {
    @Test fun `a live terminal keeps the service`() {
        assertTrue(TerminalServicePolicy.shouldRun(1))
        assertTrue(TerminalServicePolicy.shouldRun(5))
    }

    @Test fun `no live terminal means no service, so ended shells never keep the app resident`() {
        assertFalse(TerminalServicePolicy.shouldRun(0))
        assertFalse(TerminalServicePolicy.shouldRun(-1))
    }
}
