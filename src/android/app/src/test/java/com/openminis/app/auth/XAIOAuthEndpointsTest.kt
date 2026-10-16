package com.openminis.app.auth

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * xAI 设备码登录的纯逻辑：端点信任、旧端点识别、discovery 解析、令牌包整理。
 * 真实 xAI 账号 / 真机的登录与刷新**未验证**，这里只钉住不依赖网络的规则与拒绝路径。
 */
class XAIOAuthEndpointsTest {

    // ── isTrusted ───────────────────────────────────────────────────────

    @Test
    fun `trusts https x ai and its subdomains`() {
        assertTrue(XAIOAuthEndpoints.isTrusted("https://auth.x.ai/oauth2/token"))
        assertTrue(XAIOAuthEndpoints.isTrusted("https://accounts.x.ai/device?user_code=ABCD"))
        assertTrue(XAIOAuthEndpoints.isTrusted("https://x.ai/device"))
        assertTrue("host compare is case-insensitive", XAIOAuthEndpoints.isTrusted("https://AUTH.X.AI/oauth2/token"))
    }

    @Test
    fun `rejects cleartext, lookalike hosts and userinfo spoofing`() {
        assertFalse("http is never an OAuth surface", XAIOAuthEndpoints.isTrusted("http://auth.x.ai/oauth2/token"))
        assertFalse(XAIOAuthEndpoints.isTrusted("https://evil.com/oauth2/token"))
        assertFalse("suffix without a dot boundary", XAIOAuthEndpoints.isTrusted("https://evilx.ai/oauth2/token"))
        assertFalse(XAIOAuthEndpoints.isTrusted("https://x.ai.evil.com/oauth2/token"))
        assertFalse("userinfo must not make evil.com look like x.ai", XAIOAuthEndpoints.isTrusted("https://auth.x.ai@evil.com/token"))
        assertFalse(XAIOAuthEndpoints.isTrusted("not a url"))
        assertFalse(XAIOAuthEndpoints.isTrusted(""))
    }

    // ── 旧端点 ──────────────────────────────────────────────────────────

    @Test
    fun `recognises the retired token endpoint`() {
        assertTrue(XAIOAuthEndpoints.isLegacyTokenEndpoint("https://auth.x.ai/oauth/token"))
        assertTrue(XAIOAuthEndpoints.isLegacyTokenEndpoint("https://auth.x.ai/oauth/token?x=1"))
        assertFalse(XAIOAuthEndpoints.isLegacyTokenEndpoint("https://auth.x.ai/oauth2/token"))
        assertFalse(XAIOAuthEndpoints.isLegacyTokenEndpoint("https://other.x.ai/oauth/token"))
    }

    @Test
    fun `cached token endpoint is reused only when trusted and current`() {
        assertTrue(XAIOAuthEndpoints.isUsableCachedTokenEndpoint("https://auth.x.ai/oauth2/token"))
        // 老版本登录留下的缓存：必须重新发现，否则刷新一直 403。
        assertFalse(XAIOAuthEndpoints.isUsableCachedTokenEndpoint("https://auth.x.ai/oauth/token"))
        assertFalse(XAIOAuthEndpoints.isUsableCachedTokenEndpoint("https://evil.com/oauth2/token"))
        assertFalse(XAIOAuthEndpoints.isUsableCachedTokenEndpoint(""))
        assertFalse(XAIOAuthEndpoints.isUsableCachedTokenEndpoint(null))
    }

    // ── parseDiscovery ──────────────────────────────────────────────────

    private fun discovery(device: String?, token: String?) = JSONObject().apply {
        device?.let { put("device_authorization_endpoint", it) }
        token?.let { put("token_endpoint", it) }
    }

    @Test
    fun `parses the real discovery shape`() {
        val d = XAIOAuthEndpoints.parseDiscovery(
            discovery("https://auth.x.ai/oauth2/device/code", "https://auth.x.ai/oauth2/token"),
        )
        assertEquals("https://auth.x.ai/oauth2/device/code", d.deviceAuthorizationEndpoint)
        assertEquals("https://auth.x.ai/oauth2/token", d.tokenEndpoint)
    }

    private fun assertDiscoveryRejected(json: JSONObject, messagePart: String) {
        try {
            XAIOAuthEndpoints.parseDiscovery(json)
            fail("expected rejection: $messagePart")
        } catch (e: IllegalStateException) {
            assertTrue(e.message, e.message!!.contains(messagePart))
        }
    }

    @Test
    fun `rejects discovery with missing or untrusted or retired endpoints`() {
        assertDiscoveryRejected(discovery(null, "https://auth.x.ai/oauth2/token"), "missing")
        assertDiscoveryRejected(discovery("https://auth.x.ai/oauth2/device/code", null), "missing")
        assertDiscoveryRejected(
            discovery("https://evil.com/device", "https://auth.x.ai/oauth2/token"),
            "untrusted device authorization endpoint",
        )
        assertDiscoveryRejected(
            discovery("https://auth.x.ai/oauth2/device/code", "https://evil.com/token"),
            "untrusted token endpoint",
        )
        assertDiscoveryRejected(
            discovery("https://auth.x.ai/oauth2/device/code", "http://auth.x.ai/oauth2/token"),
            "untrusted token endpoint",
        )
        assertDiscoveryRejected(
            discovery("https://auth.x.ai/oauth2/device/code", "https://auth.x.ai/oauth/token"),
            "retired",
        )
    }

    // ── trustedAuthorization ────────────────────────────────────────────

    private fun auth(uri: String, complete: String?) = KimiDeviceFlow.DeviceAuthorization(
        deviceCode = "dc",
        userCode = "ABCD-1234",
        verificationUri = uri,
        verificationUriComplete = complete,
        expiresInSeconds = 1800,
        intervalSeconds = 5,
    )

    @Test
    fun `verification pages must live under x ai`() {
        val ok = auth("https://accounts.x.ai/device", "https://accounts.x.ai/device?user_code=ABCD-1234")
        assertNotNull(XAIOAuthEndpoints.trustedAuthorization(ok))
        assertNotNull(XAIOAuthEndpoints.trustedAuthorization(auth("https://accounts.x.ai/device", null)))
        // 钓鱼：服务端（或中间人）把验证页指向别处，用户会把授权码交给别人。
        assertNull(XAIOAuthEndpoints.trustedAuthorization(auth("https://evil.com/device", null)))
        assertNull(
            XAIOAuthEndpoints.trustedAuthorization(
                auth("https://accounts.x.ai/device", "https://evil.com/device?user_code=ABCD-1234"),
            ),
        )
    }

    // ── buildTokenBundle ────────────────────────────────────────────────

    private val now = 1_000_000L

    @Test
    fun `login bundle keeps id_token and stamps expiry`() {
        val response = JSONObject(
            """{"access_token":"at","refresh_token":"rt","id_token":"idt","expires_in":3600,"token_type":"Bearer"}""",
        )
        val bundle = XAIOAuthEndpoints.buildTokenBundle(response, now, requireRefreshToken = true)
        assertEquals("at", bundle.getString("access_token"))
        assertEquals("rt", bundle.getString("refresh_token"))
        assertEquals("idt", bundle.getString("id_token"))
        assertEquals(now + 3_600_000L, bundle.getLong("expire_at"))
        assertEquals(3600L, bundle.getLong("expires_in"))
    }

    @Test
    fun `login without refresh_token is refused`() {
        val response = JSONObject("""{"access_token":"at","expires_in":3600}""")
        try {
            XAIOAuthEndpoints.buildTokenBundle(response, now, requireRefreshToken = true)
            fail("a login that cannot be renewed must fail now, not after expiry")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("refresh_token"))
        }
        // 空串等同缺失。
        try {
            XAIOAuthEndpoints.buildTokenBundle(
                JSONObject("""{"access_token":"at","refresh_token":""}"""), now, requireRefreshToken = true,
            )
            fail("blank refresh_token counts as missing")
        } catch (_: IllegalStateException) {
        }
    }

    @Test
    fun `response without access_token is refused`() {
        try {
            XAIOAuthEndpoints.buildTokenBundle(JSONObject("""{"refresh_token":"rt"}"""), now, requireRefreshToken = false)
            fail("no access_token is not a credential")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("access_token"))
        }
    }

    @Test
    fun `refresh keeps the previous refresh_token when the server omits it`() {
        val response = JSONObject("""{"access_token":"at2","expires_in":1800}""")
        val bundle = XAIOAuthEndpoints.buildTokenBundle(
            response, now, requireRefreshToken = false, previousRefreshToken = "old-rt",
        )
        assertEquals("old-rt", bundle.getString("refresh_token"))
        assertEquals(now + 1_800_000L, bundle.getLong("expire_at"))
    }

    @Test
    fun `refresh adopts a rotated refresh_token`() {
        val response = JSONObject("""{"access_token":"at2","refresh_token":"new-rt","expires_in":1800}""")
        val bundle = XAIOAuthEndpoints.buildTokenBundle(
            response, now, requireRefreshToken = false, previousRefreshToken = "old-rt",
        )
        assertEquals("new-rt", bundle.getString("refresh_token"))
    }

    @Test
    fun `bundle does not mutate the response and omits expiry when expires_in is absent`() {
        val response = JSONObject("""{"access_token":"at","refresh_token":"rt"}""")
        val bundle = XAIOAuthEndpoints.buildTokenBundle(response, now, requireRefreshToken = true)
        assertFalse(bundle.has("expire_at"))
        assertFalse(response.has("expire_at"))
    }
}
