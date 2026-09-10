package com.openminis.app.runtime.ubuntu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
}
