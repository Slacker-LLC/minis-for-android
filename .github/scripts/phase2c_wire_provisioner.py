#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
MAIN = ROOT / "src/android/app/src/main/java"
TEST = ROOT / "src/android/app/src/test/java"

kernel = MAIN / "com/openminis/app/runtime/ubuntu/UbuntuKernel.kt"
text = kernel.read_text(encoding="utf-8")
needle = '''        val migrated = migrateRootOwnedUserDataLocked(ctx)\n'''
insert = '''        val provisioned = UbuntuProvisioner.ensureProvisioned(ctx)\n        if (!provisioned.ready) {\n            return@withLock Status(\n                false,\n                error = provisioned.detail ?: "Ubuntu package provisioning failed",\n            )\n        }\n\n        val migrated = migrateRootOwnedUserDataLocked(ctx)\n'''
if needle not in text:
    raise SystemExit("UbuntuKernel provision insertion point missing")
text = text.replace(needle, insert, 1)
kernel.write_text(text, encoding="utf-8")

test = TEST / "com/openminis/app/runtime/ubuntu/UbuntuProvisionerTest.kt"
test.parent.mkdir(parents=True, exist_ok=True)
test.write_text(r'''package com.openminis.app.runtime.ubuntu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UbuntuProvisionerTest {
    @Test
    fun `package set matches the historical Ubuntu runtime contract`() {
        assertEquals(
            listOf(
                "gawk",
                "python3",
                "python3-pip",
                "python3-venv",
                "git",
                "curl",
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
        assertTrue(script.contains("exec unshare -m"))
        assertTrue(script.contains("exec chroot"))
        assertTrue(script.contains("APT::Sandbox::User=root"))
        assertTrue(script.contains("apt-get"))
        assertTrue(script.contains("etc/minis/provisioned"))
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
        for (name in listOf("python3", "git", "curl", "wget", "gawk", "zip", "unzip", "xz", "zstd")) {
            assertTrue("missing $name probe", probe.contains(name))
        }
        assertTrue(probe.contains("-m pip --version"))
    }
}
''', encoding="utf-8")

print("phase2c provisioner wiring applied")
