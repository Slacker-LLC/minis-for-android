package io.github.slackerllc.minis.runtime

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import io.github.slackerllc.minis.runtime.distribution.RuntimeDistributionManifest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Device gate for the Package Manager-owned runtime layout. */
class RuntimeInstalledLayoutTest {
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sha256Asset(path: String): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(path).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    @Test
    fun apkOwnedMinisdAndRootfsMatchInstalledRuntimeManifest() {
        assumeTrue(Build.SUPPORTED_ABIS.contains("arm64-v8a"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val nativeDir = File(context.applicationInfo.nativeLibraryDir)
        val binary = File(nativeDir, "libminisd.so")

        assertTrue("nativeLibraryDir must exist", nativeDir.isDirectory)
        assertTrue("Package Manager must extract libminisd.so", binary.isFile)
        assertTrue("APK-owned minisd must be executable", binary.canExecute())
        assertFalse("minisd must not execute from filesDir", binary.canonicalPath.startsWith(context.filesDir.canonicalPath))
        assertFalse("minisd must not execute from cacheDir", binary.canonicalPath.startsWith(context.cacheDir.canonicalPath))

        val raw = context.assets.open(RuntimeDistributionManifest.ASSET_PATH)
            .bufferedReader().use { it.readText() }
        val manifest = JSONObject(raw)
        assertEquals(2, manifest.getInt("schemaVersion"))
        assertEquals(2, manifest.getInt("layoutVersion"))
        assertEquals("arm64-v8a", manifest.getString("abi"))
        assertEquals(manifest.getString("minisdSha256"), sha256(binary))
        assertEquals(
            manifest.getString("rootfsSha256"),
            sha256Asset(RuntimeDistributionManifest.ROOTFS_ASSET_PATH),
        )
        assertTrue(manifest.getJSONArray("requiredCommands").length() > 0)
    }
}
