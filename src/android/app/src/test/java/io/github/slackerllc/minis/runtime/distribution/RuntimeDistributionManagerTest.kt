package io.github.slackerllc.minis.runtime.distribution

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeDistributionManagerTest {
    @Test
    fun `runtime distribution only accepts arm64 capable devices`() {
        assertTrue(RuntimeDistributionManager.supportsArm64(listOf("arm64-v8a")))
        assertTrue(RuntimeDistributionManager.supportsArm64(listOf("x86_64", "arm64-v8a")))
        assertFalse(RuntimeDistributionManager.supportsArm64(listOf("x86_64")))
        assertFalse(RuntimeDistributionManager.supportsArm64(emptyList()))
    }

    @Test
    fun `required guest command names are shell words only`() {
        assertTrue(RuntimeDistributionManager.shellWord("python3") == "python3")
        assertTrue(RuntimeDistributionManager.shellWord("git-lfs") == "git-lfs")
        runCatching { RuntimeDistributionManager.shellWord("curl;reboot") }
            .onSuccess { error("shell metacharacters must be rejected") }
        runCatching { RuntimeDistributionManager.shellWord("../sh") }
            .onSuccess { error("path traversal must be rejected") }
        runCatching { RuntimeDistributionManager.shellWord("") }
            .onSuccess { error("empty command must be rejected") }
    }
}
