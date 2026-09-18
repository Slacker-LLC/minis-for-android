package com.openminis.app.runtime

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyRuntimeAssetGuardTest {
    private fun appProjectDir(): File {
        val cwd = File(System.getProperty("user.dir")).canonicalFile
        val candidates = listOf(
            cwd,
            File(cwd, "app"),
            File(cwd, "src/android/app"),
        )
        return candidates.firstOrNull { File(it, "src/main/AndroidManifest.xml").isFile }
            ?: error("cannot locate Android app project from ${cwd.path}")
    }

    @Test
    fun obsoleteRuntimeAssetsAreNotPackaged() {
        val app = appProjectDir()
        assertFalse(
            "legacy default_mount overlay must not return to Android production assets",
            File(app, "src/main/assets/default_mount").exists(),
        )
        assertFalse(
            "legacy minisd policy must not return to the direct Ubuntu Android package",
            File(app, "src/main/assets/minisd-policy.json").exists(),
        )
    }

    @Test
    fun obsoleteAndroidRuntimePackagesAreGone() {
        val app = appProjectDir()
        val runtime = File(app, "src/main/java/com/openminis/app/runtime")
        assertFalse(
            "legacy minisd Android client package must not return",
            File(runtime, "minisd").exists(),
        )
        assertFalse(
            "legacy runtime distribution package must not return",
            File(runtime, "distribution").exists(),
        )
    }

    @Test
    fun currentAndroidAssetsDirectoryStillExists() {
        val app = appProjectDir()
        assertTrue(File(app, "src/main/assets").isDirectory)
    }
}
