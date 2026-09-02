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
    fun obsoleteDefaultMountOverlayIsNotPackaged() {
        val app = appProjectDir()
        assertFalse(
            "legacy default_mount overlay must not return to Android production assets",
            File(app, "src/main/assets/default_mount").exists(),
        )
    }

    @Test
    fun currentAndroidAssetsDirectoryStillExists() {
        val app = appProjectDir()
        assertTrue(File(app, "src/main/assets").isDirectory)
    }
}