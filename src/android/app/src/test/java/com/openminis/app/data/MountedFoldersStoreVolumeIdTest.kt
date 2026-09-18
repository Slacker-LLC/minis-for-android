package com.openminis.app.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MountedFoldersStoreVolumeIdTest {
    @Test
    fun `safe non-RFC volume ids are accepted`() {
        assertTrue(isSafeStorageVolumeId("1234-ABCD"))
        assertTrue(isSafeStorageVolumeId("portable-volume"))
        assertTrue(isSafeStorageVolumeId("550e8400-e29b-41d4-a716-446655440000"))
    }

    @Test
    fun `unsafe volume ids are rejected`() {
        assertFalse(isSafeStorageVolumeId(""))
        assertFalse(isSafeStorageVolumeId("."))
        assertFalse(isSafeStorageVolumeId(".."))
        assertFalse(isSafeStorageVolumeId("../escape"))
        assertFalse(isSafeStorageVolumeId("a/b"))
        assertFalse(isSafeStorageVolumeId("a\\b"))
        assertFalse(isSafeStorageVolumeId("a\u0000b"))
    }
}
