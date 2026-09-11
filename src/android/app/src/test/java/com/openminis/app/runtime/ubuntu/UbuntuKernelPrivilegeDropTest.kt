package com.openminis.app.runtime.ubuntu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UbuntuKernelPrivilegeDropTest {
    @Test
    fun `guest launch forbids privilege regain before exec`() {
        val command = UbuntuKernel.buildGuestSetprivExec(
            uid = 10234,
            envArgs = "'HOME=/home/minis'",
            shellArgs = "/bin/bash --noprofile --norc",
        )

        assertTrue(command.contains("--reuid=10234 --regid=10234 --clear-groups"))
        assertTrue(command.contains("--inh-caps=-all --ambient-caps=-all --bounding-set=-all"))
        assertTrue(command.contains("--no-new-privs"))
        assertTrue(command.indexOf("--no-new-privs") < command.indexOf("/usr/bin/env -i"))
        assertFalse(command.contains("--keep-groups"))
    }
}
