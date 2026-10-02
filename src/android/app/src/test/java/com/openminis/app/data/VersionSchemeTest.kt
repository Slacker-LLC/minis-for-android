package com.openminis.app.data

import com.openminis.app.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the release version scheme (docs/development/RELEASING.md): `versionCode` is derived from
 * `versionName` in app/build.gradle.kts, and must order exactly like the version does for the updater.
 */
class VersionSchemeTest {
    /** Same formula as `appVersionCode` in app/build.gradle.kts; a drift between the two fails the test below. */
    private fun expectedCode(versionName: String): Int {
        val m = Regex("""^(\d+)\.(\d+)(?:\.(\d+))?(?:-(dev|beta\.(\d+)))?$""").matchEntire(versionName)
            ?: error("not a release version: $versionName")
        val (major, minor, patch, label, beta) = m.destructured
        val stage = when {
            label.isEmpty() -> 99
            label == "dev" -> 0
            else -> beta.toInt()
        }
        return major.toInt() * 1_000_000 + minor.toInt() * 10_000 + patch.ifEmpty { "0" }.toInt() * 100 + stage
    }

    @Test
    fun `the built versionCode is the one the scheme derives from versionName`() {
        assertEquals(expectedCode(BuildConfig.VERSION_NAME), BuildConfig.VERSION_CODE)
    }

    @Test
    fun `codes in the release doc`() {
        assertEquals(1_000_099, expectedCode("1.0"))
        assertEquals(1_000_199, expectedCode("1.0.1"))
        assertEquals(1_010_000, expectedCode("1.1-dev"))
        assertEquals(1_010_001, expectedCode("1.1-beta.1"))
        assertEquals(1_010_099, expectedCode("1.1"))
        assertEquals(1_010_101, expectedCode("1.1.1-beta.1"))
    }

    @Test
    fun `every code is above the builds that shipped before 1_0`() {
        // The last pre-1.0 build was versionCode 39; 1.0 must install over it.
        assertTrue(expectedCode("1.0") > 39)
        assertTrue(expectedCode("1.1-dev") > expectedCode("1.0"))
    }

    @Test
    fun `versionCode and the updater agree on which version is newer`() {
        val versions = listOf(
            "1.0", "1.0.1", "1.0.2", "1.1-dev", "1.1-beta.1", "1.1-beta.2", "1.1-beta.10", "1.1",
            "1.1.1-beta.1", "1.1.1", "1.2-dev", "1.2-beta.1", "1.2", "2.0-dev", "2.0",
        )
        for (a in versions) for (b in versions) {
            val byCode = expectedCode(a).compareTo(expectedCode(b))
            val byUpdater = UpdateChecker.compareVersions(
                UpdateChecker.normalizeTag(a),
                UpdateChecker.normalizeTag(b),
            ).coerceIn(-1, 1)
            assertEquals("$a vs $b", byCode, byUpdater)
        }
    }
}
