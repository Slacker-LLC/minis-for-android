package com.openminis.app.debug

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

/**
 * [T-android-remote-import-ssrf] The address policy behind every URL import
 * (skills, MCP config, and the fetch tool).
 *
 * The device pass caught it refusing everything: this network answers every name
 * with a 198.18.x.x fake IP, and the guard forbade the whole benchmark range, so
 * `skills.importUrl https://example.com/` replied "URL host does not resolve to a
 * public address" without fetching anything, while GitHub URLs (which take the
 * API path) worked. The range stays blocked for literal addresses, where no proxy
 * is in front to route the connection.
 */
class SafeRemoteImportPolicyTest {

    private fun addr(text: String): InetAddress = InetAddress.getByName(text)

    @Test
    fun `a fake-IP answer for a name is allowed`() {
        assertFalse(SafeRemoteImporter.isForbiddenAddress(addr("198.18.5.94"), fromHostname = true))
        assertFalse(SafeRemoteImporter.isForbiddenAddress(addr("198.19.255.1"), fromHostname = true))
    }

    @Test
    fun `a literal address in the URL is refused`() {
        assertTrue(SafeRemoteImporter.isForbiddenAddress(addr("198.18.5.94"), fromHostname = false))
        assertTrue(SafeRemoteImporter.isForbiddenAddress(addr("198.19.255.1"), fromHostname = false))
    }

    @Test
    fun `genuinely private answers are refused either way`() {
        val private = listOf(
            "192.168.8.1", "10.0.0.5", "172.16.4.4", "127.0.0.1",
            "169.254.10.10", "100.64.1.1", "0.0.0.0", "255.255.255.255",
        )
        for (text in private) {
            assertTrue(text, SafeRemoteImporter.isForbiddenAddress(addr(text), fromHostname = true))
            assertTrue(text, SafeRemoteImporter.isForbiddenAddress(addr(text), fromHostname = false))
        }
        for (text in listOf("fd00::1", "::1")) {
            assertTrue(text, SafeRemoteImporter.isForbiddenAddress(addr(text), fromHostname = true))
        }
    }

    @Test
    fun `public answers keep working`() {
        for (text in listOf("93.184.216.34", "140.82.121.4", "2606:4700::1111")) {
            assertFalse(text, SafeRemoteImporter.isForbiddenAddress(addr(text), fromHostname = true))
            assertFalse(text, SafeRemoteImporter.isForbiddenAddress(addr(text), fromHostname = false))
        }
    }
}

