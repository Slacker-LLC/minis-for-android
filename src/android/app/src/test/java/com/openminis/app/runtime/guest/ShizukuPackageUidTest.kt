package com.openminis.app.runtime.guest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShizukuPackageUidTest {
    private val dump = """
        Packages:
          Package [com.example.app] (abc123):
            userId=10234
            pkg=Package{def com.example.app}
    """.trimIndent()

    @Test
    fun theUidIsReadFromADumpOfAnInstalledPackage() {
        assertEquals("10234", ShizukuOffloadHandler.parsePackageUid(dump, 0))
    }

    @Test
    fun aFailedCommandIsNotAUid() {
        assertNull(ShizukuOffloadHandler.parsePackageUid(dump, 1))
        assertNull(ShizukuOffloadHandler.parsePackageUid(dump, 124))
    }

    @Test
    fun anUnknownPackageDoesNotFallBackToZero() {
        assertNull(ShizukuOffloadHandler.parsePackageUid("Unable to find package: com.nope", 0))
        assertNull(ShizukuOffloadHandler.parsePackageUid("", 0))
    }

    @Test
    fun uidZeroAndOtherSystemRangeValuesBelowTheAppRangeAreRefused() {
        assertNull(ShizukuOffloadHandler.parsePackageUid("userId=0", 0))
        assertNull(ShizukuOffloadHandler.parsePackageUid("userId=999", 0))
        assertEquals("1000", ShizukuOffloadHandler.parsePackageUid("userId=1000", 0))
    }
}
