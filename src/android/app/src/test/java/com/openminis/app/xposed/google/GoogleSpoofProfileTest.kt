package com.openminis.app.xposed.google

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-eta-xposed-groups] Ported from Eta `hook/google/GoogleEligibilityHooks.kt` and the identity
 * half of `hook/google/GoogleAppHooks.kt` (Mangi-11/Eta @ c15de97). The profile is the kind of
 * table where a typo is invisible - a name that does not match leaves the real answer in place while
 * the hook reports success - so the match itself is what these cases pin down.
 */
class GoogleSpoofProfileTest {

    @Test
    fun `only the eligible-device property is answered`() {
        assertTrue(GoogleSpoofProfile.forgedProperty("ro.opa.eligible_device"))
        assertFalse(
            "a prefix of the key is a different key",
            GoogleSpoofProfile.forgedProperty("ro.opa.eligible_device_x"),
        )
        assertFalse(GoogleSpoofProfile.forgedProperty("ro.build.type"))
        assertFalse(GoogleSpoofProfile.forgedProperty(null))
    }

    @Test
    fun `only the two Google experience features are answered`() {
        assertTrue(GoogleSpoofProfile.forgedFeature("com.google.android.feature.GOOGLE_BUILD"))
        assertTrue(GoogleSpoofProfile.forgedFeature("com.google.android.feature.GOOGLE_EXPERIENCE"))
        assertFalse(GoogleSpoofProfile.forgedFeature("com.google.android.feature.GOOGLE_BUILD_X"))
        assertFalse(GoogleSpoofProfile.forgedFeature("android.hardware.camera"))
        assertFalse(GoogleSpoofProfile.forgedFeature(null))
    }

    @Test
    fun `the identity is the five uppercase statics the platform reads`() {
        assertEquals(
            listOf("MANUFACTURER", "BRAND", "MODEL", "PRODUCT", "DEVICE"),
            GoogleSpoofProfile.BUILD_FIELDS.keys.toList(),
        )
        assertTrue(
            "a lowercase or misspelled field would leave the real one in place",
            GoogleSpoofProfile.BUILD_FIELDS.keys.all { it == it.uppercase() },
        )
        assertTrue(GoogleSpoofProfile.BUILD_FIELDS.values.none { it.isBlank() })
    }
}
