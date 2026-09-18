package com.openminis.app.runtime.ubuntu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UbuntuProvisionerTest {
    @Test
    fun `provision failure backoff is short and expires without cumulative lockout`() {
        assertEquals(
            UbuntuProvisioner.PROVISION_BACKOFF_MS,
            UbuntuProvisioner.provisionBackoffRemaining(10_000L, 10_000L),
        )
        assertEquals(
            5_000L,
            UbuntuProvisioner.provisionBackoffRemaining(15_000L, 10_000L, 10_000L),
        )
        assertEquals(
            0L,
            UbuntuProvisioner.provisionBackoffRemaining(20_001L, 10_000L, 10_000L),
        )
        assertEquals(
            0L,
            UbuntuProvisioner.provisionBackoffRemaining(20_000L, 0L),
        )
    }

    @Test
    fun `package set matches the current Ubuntu runtime contract`() {
        assertEquals(
            listOf(
                "gawk",
                "python3",
                "python3-pip",
                "python3-venv",
                "git",
                "curl",
                "iputils-ping",
                "wget",
                "ca-certificates",
                "zip",
                "unzip",
                "xz-utils",
                "zstd",
            ),
            UbuntuProvisioner.BASE_PACKAGES,
        )
    }

    @Test
    fun `provision command is bounded one shot root maintenance without broker`() {
        val script = UbuntuProvisioner.buildProvisionCommand(
            "/data/adb/minis/rootfs",
            "",
        )
        assertTrue(script.contains("exec /system/bin/unshare -m"))
        assertTrue(script.contains("exec /system/bin/chroot"))
        assertTrue(script.contains("mount -o rprivate,bind / /"))
        assertTrue(script.contains("APT::Sandbox::User=root"))
        assertTrue(script.contains("Acquire::Retries=1"))
        assertTrue(script.contains("Acquire::http::Timeout=30"))
        assertTrue(script.contains("Acquire::https::Timeout=30"))
        assertTrue(script.contains("apt-get"))
        assertTrue(script.contains("etc/minis/provisioned"))
        assertTrue(script.contains("test ! -L /etc"))
        assertTrue(script.contains("if [ -L /etc/minis/provisioned ]; then exit 73"))
        assertFalse(script.contains("minisd"))
        assertFalse(script.contains("--socket"))
        assertFalse(script.contains("keeper"))
    }

    @Test
    fun `proxy is injected only when explicitly supplied`() {
        val direct = UbuntuProvisioner.buildProvisionCommand("/data/adb/minis/rootfs", "")
        assertFalse(direct.contains("Acquire::http::Proxy="))
        val proxied = UbuntuProvisioner.buildProvisionCommand(
            "/data/adb/minis/rootfs",
            "http://127.0.0.1:7890",
        )
        assertTrue(proxied.contains("Acquire::http::Proxy=http://127.0.0.1:7890"))
        assertTrue(proxied.contains("Acquire::https::Proxy=http://127.0.0.1:7890"))
    }

    @Test
    fun `ready probe requires marker and installed command surface`() {
        val probe = UbuntuProvisioner.buildProbeCommand("/data/adb/minis/rootfs")
        assertTrue(probe.contains("etc/minis/provisioned"))
        for (name in listOf("python3", "git", "curl", "ping", "wget", "gawk", "zip", "unzip", "xz", "zstd")) {
            assertTrue("missing $name probe", probe.contains(name))
        }
        assertTrue(probe.contains("-m pip --version"))
        assertTrue(probe.contains("/system/bin/chroot '/data/adb/minis/rootfs' /usr/bin/python3"))
    }
}
