package com.openminis.app.runtime.guest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RootCliPackageUidTest {
    private val dump = """
        Packages:
          Package [com.example.app] (abc123):
            userId=10234
            pkg=Package{def com.example.app}
    """.trimIndent()

    @Test
    fun theUidIsReadFromADumpOfAnInstalledPackage() {
        assertEquals("10234", RootCliOffloadHandler.parsePackageUid(dump, 0))
    }

    @Test
    fun aFailedCommandIsNotAUid() {
        assertNull(RootCliOffloadHandler.parsePackageUid(dump, 1))
        assertNull(RootCliOffloadHandler.parsePackageUid(dump, 124))
    }

    @Test
    fun anUnknownPackageDoesNotFallBackToZero() {
        assertNull(RootCliOffloadHandler.parsePackageUid("Unable to find package: com.nope", 0))
        assertNull(RootCliOffloadHandler.parsePackageUid("", 0))
    }

    @Test
    fun uidZeroAndOtherSystemRangeValuesBelowTheAppRangeAreRefused() {
        assertNull(RootCliOffloadHandler.parsePackageUid("userId=0", 0))
        assertNull(RootCliOffloadHandler.parsePackageUid("userId=999", 0))
        assertEquals("1000", RootCliOffloadHandler.parsePackageUid("userId=1000", 0))
    }
}
