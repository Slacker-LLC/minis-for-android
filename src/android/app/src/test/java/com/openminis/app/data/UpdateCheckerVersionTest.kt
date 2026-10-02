package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerVersionTest {
    @Test
    fun `pet build and matching release normalize identically`() {
        assertEquals("1.12.pet.15", UpdateChecker.normalizeTag("1.12-pet.15-SNAPSHOT"))
        assertEquals("1.12.pet.15", UpdateChecker.normalizeTag("v1.12-pet.15"))
    }

    @Test
    fun `pet counter remains part of update ordering`() {
        val local = UpdateChecker.normalizeTag("1.12-pet.15-SNAPSHOT")
        val older = UpdateChecker.normalizeTag("v1.12-pet.14")
        val newer = UpdateChecker.normalizeTag("v1.12-pet.16")

        assertTrue(UpdateChecker.compareVersions(local, older) > 0)
        assertTrue(UpdateChecker.compareVersions(newer, local) > 0)
        assertEquals(0, UpdateChecker.compareVersions(local, UpdateChecker.normalizeTag("v1.12-pet.15")))
    }

    @Test
    fun `ordinary labels stay equal to their numeric base and legacy rc is old`() {
        assertEquals("1.12.3", UpdateChecker.normalizeTag("v1.12.3-preview"))
        assertEquals("1.12.3", UpdateChecker.normalizeTag("1.12.3-rc1"))
        assertTrue(
            UpdateChecker.compareVersions(
                UpdateChecker.normalizeTag("v1.12-pet.15"),
                UpdateChecker.normalizeTag("rc9"),
            ) > 0,
        )
    }

    @Test
    fun `release 1_0 is up to date on itself and older than every later release`() {
        val local = UpdateChecker.normalizeTag("1.0")
        assertEquals(0, UpdateChecker.compareVersions(UpdateChecker.normalizeTag("v1.0"), local))
        assertTrue(UpdateChecker.compareVersions(UpdateChecker.normalizeTag("v1.0.1"), local) > 0)
        assertTrue(UpdateChecker.compareVersions(UpdateChecker.normalizeTag("v1.1"), local) > 0)
        assertTrue(UpdateChecker.compareVersions(UpdateChecker.normalizeTag("v0.22-preview"), local) < 0)
    }

    @Test
    fun `dev and beta stages keep their label and counter`() {
        assertEquals("1.1-beta.2", UpdateChecker.normalizeTag("v1.1-beta.2"))
        assertEquals("1.1-beta.2", UpdateChecker.normalizeTag("1.1-BETA.2"))
        assertEquals("1.1-beta.1", UpdateChecker.normalizeTag("1.1-beta"))
        assertEquals("1.1.2-beta.3", UpdateChecker.normalizeTag("v1.1.2-beta.3"))
        assertEquals("1.1-dev", UpdateChecker.normalizeTag("1.1-dev"))
        assertEquals("1.1-dev", UpdateChecker.normalizeTag("1.1-dev-SNAPSHOT"))
    }

    @Test
    fun `versions order dev then betas then the final then patches`() {
        val order = listOf("1.0", "1.0.1", "1.1-dev", "1.1-beta.1", "1.1-beta.2", "1.1-beta.10", "1.1", "1.1.1-beta.1", "1.1.1", "1.2-dev")
            .map { UpdateChecker.normalizeTag(it) }
        for (i in order.indices) for (j in order.indices) {
            val expected = i.compareTo(j)
            assertEquals("${order[i]} vs ${order[j]}", expected, UpdateChecker.compareVersions(order[i], order[j]).coerceIn(-1, 1))
        }
    }

    @Test
    fun `a beta is offered the next beta and then the final, never the reverse`() {
        val beta1 = UpdateChecker.normalizeTag("v1.1-beta.1")
        assertTrue(UpdateChecker.compareVersions(UpdateChecker.normalizeTag("v1.1-beta.2"), beta1) > 0)
        assertTrue(UpdateChecker.compareVersions(UpdateChecker.normalizeTag("v1.1"), beta1) > 0)
        assertTrue(UpdateChecker.compareVersions(beta1, UpdateChecker.normalizeTag("v1.1")) < 0)
        // A patch for the previous line is not an upgrade for someone on the new beta.
        assertTrue(UpdateChecker.compareVersions(UpdateChecker.normalizeTag("v1.0.1"), beta1) < 0)
    }

    @Test
    fun `a stable build is only offered stable releases`() {
        assertTrue(UpdateChecker.isVisibleToChannel("1.0", "v1.0.1", flaggedPrerelease = false))
        assertFalse(UpdateChecker.isVisibleToChannel("1.0", "v1.1-beta.1", flaggedPrerelease = false))
        // GitHub's own prerelease flag hides it too, whatever the tag looks like.
        assertFalse(UpdateChecker.isVisibleToChannel("1.0", "v1.1", flaggedPrerelease = true))
        assertFalse(UpdateChecker.isVisibleToChannel("1.0.1", "v1.1-beta.1", flaggedPrerelease = true))
    }

    @Test
    fun `a beta or dev build is offered prereleases and finals`() {
        for (local in listOf("1.1-beta.1", "1.1-dev")) {
            assertTrue(UpdateChecker.isVisibleToChannel(local, "v1.1-beta.2", flaggedPrerelease = true))
            assertTrue(UpdateChecker.isVisibleToChannel(local, "v1.1", flaggedPrerelease = false))
        }
    }

    @Test
    fun `the pre-1_0 1_01 beta builds read as newer than 1_0 so the updater does not offer 1_0 over them`() {
        // Known and accepted: 1.0 is installed over such a build by hand (its versionCode is higher).
        val beta = UpdateChecker.normalizeTag("1.01-beta.2")
        assertEquals("1.01-beta.2", beta)
        assertTrue(UpdateChecker.compareVersions(beta, UpdateChecker.normalizeTag("v1.0")) > 0)
    }
}
