package com.openminis.app.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OAuthRefreshPolicyTest {

    @Test
    fun anExplicitInvalidGrantIsARevocation() {
        assertTrue(OAuthRefreshPolicy.isRevoked(400, """{"error":"invalid_grant"}"""))
        assertTrue(OAuthRefreshPolicy.isRevoked(401, """{"error":{"type":"invalid_grant","message":"x"}}"""))
        assertTrue(OAuthRefreshPolicy.isRevoked(400, """{"error":"refresh_token_reused"}"""))
        assertTrue(OAuthRefreshPolicy.isRevoked(400, """{"error":"INVALID_GRANT"}"""))
    }

    @Test
    fun otherRfcErrorsAreNotARevocation() {
        assertFalse(OAuthRefreshPolicy.isRevoked(400, """{"error":"invalid_request"}"""))
        assertFalse(OAuthRefreshPolicy.isRevoked(401, """{"error":"invalid_client"}"""))
    }

    @Test
    fun aBare403OrAnUnparsableBodyIsNotARevocation() {
        assertFalse(OAuthRefreshPolicy.isRevoked(403, """{"message":"subscription tier"}"""))
        assertFalse(OAuthRefreshPolicy.isRevoked(400, "Bad Request"))
        assertFalse(OAuthRefreshPolicy.isRevoked(401, ""))
    }

    @Test
    fun aServerErrorWhoseTextMentionsTheRefreshTokenIsNotARevocation() {
        val body = """{"error":"temporarily_unavailable","error_description":"refresh_token service unavailable"}"""
        assertFalse(OAuthRefreshPolicy.isRevoked(503, body))
        assertFalse(OAuthRefreshPolicy.isRevoked(400, body))
    }

    @Test
    fun theErrorCodeIsReadFromBothShapes() {
        assertEquals("invalid_grant", OAuthRefreshPolicy.errorCode("""{"error":"invalid_grant"}"""))
        assertEquals("rate_limit", OAuthRefreshPolicy.errorCode("""{"error":{"code":"rate_limit"}}"""))
        assertNull(OAuthRefreshPolicy.errorCode("not json"))
        assertNull(OAuthRefreshPolicy.errorCode("""{"ok":true}"""))
    }

    @Test
    fun aOneHourTokenRefreshesAQuarterOfItsLifeBeforeExpiryNotFourHours() {
        assertEquals(15 * 60 * 1000L, OAuthRefreshPolicy.refreshLeadMs(3600))
    }

    @Test
    fun theLeadIsBoundedByFiveMinutesAndFourHours() {
        assertEquals(5 * 60 * 1000L, OAuthRefreshPolicy.refreshLeadMs(60))
        assertEquals(5 * 60 * 1000L, OAuthRefreshPolicy.refreshLeadMs(0))
        assertEquals(4 * 3600 * 1000L, OAuthRefreshPolicy.refreshLeadMs(10L * 24 * 3600))
    }
}
