package com.openminis.app.auth

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The refresh write guard against the real encrypted store. */
@RunWith(AndroidJUnit4::class)
class OAuthRefreshGuardInstrumentedTest {
    private class TestManager(context: Context, id: String) : OAuthManager(context, id) {
        override val authURL = "https://example.invalid/auth"
        override val tokenURL = "https://example.invalid/token"
        override val clientId = "test"
        override val clientSecret: String? = null
        override val callbackPort = 0
        override val redirectPath = "/cb"
        override val scopes = ""
        fun stillStoredAs(refresh: String) = storedRefreshTokenIs(refresh)
    }

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = "guard-test-${System.nanoTime()}"
    private lateinit var manager: TestManager

    @Before
    fun setUp() {
        manager = TestManager(context, id)
        manager.importOAuthString(
            "tokens",
            JSONObject().put("access_token", "a1").put("refresh_token", "r1").toString(),
        )
    }

    @After
    fun tearDown() = manager.logout()

    @Test
    fun theCredentialIsRecognisedWhileItIsUnchanged() {
        assertTrue(manager.stillStoredAs("r1"))
    }

    @Test
    fun afterSignOutALateRefreshResultMustNotBeWritten() {
        manager.logout()
        assertFalse(manager.stillStoredAs("r1"))
    }

    @Test
    fun afterAnotherRefreshRotatedTheTokenTheOlderResultIsStale() {
        manager.importOAuthString(
            "tokens",
            JSONObject().put("access_token", "a2").put("refresh_token", "r2").toString(),
        )
        assertFalse(manager.stillStoredAs("r1"))
        assertTrue(manager.stillStoredAs("r2"))
    }
}
