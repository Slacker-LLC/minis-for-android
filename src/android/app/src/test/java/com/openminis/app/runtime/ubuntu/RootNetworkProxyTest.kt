package com.openminis.app.runtime.ubuntu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RootNetworkProxyTest {
    @Test
    fun `proxy uri embeds fixed user and opaque lowercase token`() {
        val token = "0123456789abcdef".repeat(4)
        assertEquals(
            "http://minis:$token@127.0.0.1:18787",
            RootNetworkProxy.buildProxyUri(token),
        )
    }

    @Test
    fun `proxy uri rejects non canonical tokens`() {
        assertThrows(IllegalArgumentException::class.java) {
            RootNetworkProxy.buildProxyUri("A".repeat(64))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RootNetworkProxy.buildProxyUri("0".repeat(63))
        }
    }

    @Test
    fun `ready handshake accepts only the managed child announcement`() {
        assertTrue(RootNetworkProxy.isReadyAnnouncement("READY 127.0.0.1:18787"))
        assertFalse(RootNetworkProxy.isReadyAnnouncement("READY 0.0.0.0:18787"))
        assertFalse(RootNetworkProxy.isReadyAnnouncement("READY 127.0.0.1:18787 extra"))
        assertFalse(RootNetworkProxy.isReadyAnnouncement("ready 127.0.0.1:18787"))
    }
}
