package com.openminis.app.runtime.ubuntu

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
}
