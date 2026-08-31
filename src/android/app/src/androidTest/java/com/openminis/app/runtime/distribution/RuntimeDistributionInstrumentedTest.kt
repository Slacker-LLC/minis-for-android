package com.openminis.app.runtime.distribution

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.openminis.app.runtime.distribution.RuntimeDistributionManager.DeployedIdentity
import com.openminis.app.runtime.ubuntu.UbuntuRuntime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-device gate for Issue #51: ensureReady must consume the APK schema-v2
 * manifest and deploy the packaged runtime atomically, then commit the deployed
 * identity under /data/adb/minis/runtime/deployed.json. Requires an authorized
 * Root/KernelSU arm64 device; skipped on ordinary CI devices.
 */
@RunWith(AndroidJUnit4::class)
class RuntimeDistributionInstrumentedTest {

    private fun suAvailable(): Boolean = runCatching {
        val process = ProcessBuilder("/system/bin/su", "-c", "id -u")
            .redirectErrorStream(true)
            .start()
        process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        process.destroy()
        output.lineSequence().mapNotNull { it.trim().toIntOrNull() }.firstOrNull() == 0
    }.getOrDefault(false)

    private fun readHostFile(path: String): String? = runCatching {
        val process = ProcessBuilder("/system/bin/su", "-c", "cat '$path'")
            .redirectErrorStream(true)
            .start()
        process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        process.destroy()
        output.takeIf { process.exitValue() == 0 && it.isNotEmpty() }
    }.getOrNull()

    @Test
    fun ensureReadyDeploysPackagedRuntimeAndCommitsIdentity() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        UbuntuRuntime.init(context)
        val ready = UbuntuRuntime.ensureReady()
        assertTrue("ubuntu runtime should be running, got: ${ready.lastError}", ready.running)

        val manifestText = context.assets.open(RuntimePayloadVerifier.MANIFEST_ASSET)
            .bufferedReader()
            .use { it.readText() }
        val manifest = RuntimeDistributionManifest.parse(manifestText)

        val deployedRaw = readHostFile(RuntimeDistributionManager.DEPLOYED_FILE)
        assertNotNull("deployed identity must be committed", deployedRaw)
        val deployed = DeployedIdentity.parse(deployedRaw!!)
        assertTrue(
            "deployed identity must match manifest: ${deployed.rootfsVersion}",
            deployed.matches(manifest),
        )
        val pending = readHostFile(RuntimeDistributionManager.PENDING_FILE)
        assertTrue("pending transaction must be cleared", pending == null || pending.isEmpty())
    }
}
