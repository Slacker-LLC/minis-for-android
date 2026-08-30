package com.openminis.app.runtime.ubuntu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeProvisionTest {
    @Test
    fun brokerInstallCopiesPackagedLibThenRequiresExecutable() {
        val command = RuntimeProvision.installBrokerCommand(
            "/data/app/pkg/lib/arm64/libminisd.so",
        )
        assertTrue(command.contains("/data/app/pkg/lib/arm64/libminisd.so"))
        assertTrue(command.contains("/data/adb/minis/bin/minisd"))
        assertTrue(command.contains("cp \"\$SRC\" \"\$BIN.tmp\""))
        val copyAt = command.indexOf("cp \"\$SRC\"")
        val missingAt = command.indexOf("minisd missing or not executable")
        assertTrue(copyAt >= 0)
        assertTrue(missingAt > copyAt)
    }

    @Test
    fun rootfsStagingReadsAssetFromApkAndFailClosesWhenMissing() {
        val command = RuntimeProvision.stageRootfsFromApkCommand("dev.openminispet.android")
        assertTrue(command.contains("pm path"))
        assertTrue(command.contains("assets/minis-runtime/ubuntu-arm64-rootfs.tar.gz"))
        assertTrue(command.contains("/data/adb/minis/runtime/staging/ubuntu-arm64-rootfs.tar.gz"))
        assertTrue(command.contains("ROOTFS_NOT_PACKAGED"))
        assertFalse(command.contains("/data/local/tmp/ubuntu-arm64-rootfs.tar.gz"))
        val unzipAt = command.indexOf("unzip -p")
        val sizeAt = command.indexOf("[ ! -s")
        assertTrue(unzipAt >= 0)
        assertTrue(sizeAt > unzipAt)
    }
}
