package io.github.slackerllc.minis

import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidIdentityTest {
    @Test
    fun generatedApplicationIdMatchesCanonicalIdentity() {
        assertEquals("io.github.slackerllc.minis", BuildConfig.APPLICATION_ID)
    }

    @Test
    fun testPackageUsesCanonicalNamespace() {
        assertEquals("io.github.slackerllc.minis", this::class.java.packageName)
    }
}
