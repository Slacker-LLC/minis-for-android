package com.openminis.app.integrity

import com.openminis.app.runtime.ubuntu.DirectRootRunner
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProtectedRootTest {
    private val scripts = mutableListOf<String>()
    private var enabled = true
    private var storageBlocked = false
    private var runnerResult = DirectRootRunner.Result(0, "ok", "")

    @Before fun setUp() {
        scripts.clear(); enabled = true; storageBlocked = false
        runnerResult = DirectRootRunner.Result(0, "ok", "")
        IntegrityAudit.clearForTest()
        ProtectedRoot.policy = DeviceIntegrityPolicy(CorePackageResolver.StaticOnly)
        ProtectedRoot.enabled = { enabled }
        ProtectedRoot.storageBlocksWrites = { storageBlocked }
        ProtectedRoot.rootfs = "/rootfs"
        ProtectedRoot.runner = { script, _ -> scripts += script; runnerResult }
    }

    @After fun tearDown() {
        ProtectedRoot.policy = DeviceIntegrityPolicy(RootCorePackageResolver)
        ProtectedRoot.enabled = { DeviceProtectionStore.isEnabled }
        ProtectedRoot.storageBlocksWrites = { StorageGuard.writesBlocked }
        ProtectedRoot.rootfs = com.openminis.app.runtime.ubuntu.UbuntuPaths.HOST_ROOTFS
    }

    private fun argv(vararg a: String, session: String? = "s1") =
        runBlocking { ProtectedRoot.runArgv("root.shell", session, a.toList()) }

    private fun script(s: String) = runBlocking { ProtectedRoot.runScript("android-root-cli", "s1", s) }

    @Test fun `a refused argv never reaches su and returns the fixed error`() {
        val r = argv("dd", "if=/dev/zero", "of=/dev/block/by-name/boot")
        assertEquals(ProtectedRoot.EXIT_DEVICE_PROTECTED, r.exitCode)
        assertTrue(r.stderr.startsWith("DEVICE_PROTECTED: partition: "))
        assertTrue("runner must not run", scripts.isEmpty())
    }

    @Test fun `a refused exec script never reaches su`() {
        val r = script("sh -c 'rm -rf /data/system'")
        assertEquals(77, r.exitCode)
        assertTrue(scripts.isEmpty())
    }

    @Test fun `a refusal is recorded with entry point session and target`() {
        argv("rm", "-rf", "/data/system", session = "sess-9")
        val e = IntegrityAudit.entries.value.single()
        assertEquals("root.shell", e.entryPoint)
        assertEquals("sess-9", e.sessionId)
        assertEquals("data-system", e.category)
        assertEquals("/data/system", e.target)
    }

    @Test fun `an allowed command runs inside the protected view`() {
        val r = argv("rm", "-rf", "/data/data/com.example/cache")
        assertEquals(0, r.exitCode)
        val s = scripts.single()
        assertTrue(s.contains("/system/bin/unshare -m"))
        assertTrue(s.contains("rm"))
        assertTrue(s.contains("--bounding-set="))
    }

    @Test fun `a view that cannot be built is reported not hidden`() {
        runnerResult = DirectRootRunner.Result(125, "", "DEVICE_PROTECTION_UNAVAILABLE: rootfs setpriv not found")
        val r = argv("ls", "/sdcard")
        assertEquals(125, r.exitCode)
        assertTrue(ProtectedView.isUnavailable(r.exitCode, r.stderr))
    }

    @Test fun `with protection off nothing is checked or wrapped`() {
        enabled = false
        val r = argv("rm", "-rf", "/data/system")
        assertEquals(0, r.exitCode)
        assertEquals("exec 'rm' '-rf' '/data/system'", scripts.single())
        assertTrue(IntegrityAudit.entries.value.isEmpty())
    }

    @Test fun `while storage is low bulk writes are refused but deleting is not`() {
        storageBlocked = true
        val cp = argv("cp", "-r", "/sdcard/a", "/sdcard/b")
        assertEquals(77, cp.exitCode)
        assertTrue(cp.stderr.startsWith("DEVICE_PROTECTED: storage: "))
        assertEquals(0, argv("rm", "-rf", "/sdcard/big").exitCode)
        assertEquals(0, argv("ls", "/sdcard").exitCode)
    }

    @Test fun `storage refusal also covers exec scripts`() {
        storageBlocked = true
        assertEquals(77, script("dd if=/dev/zero of=/sdcard/fill bs=1M").exitCode)
        assertEquals(0, script("dd if=/dev/zero of=/dev/null count=1").exitCode)
    }

    @Test fun `checkWrite and checkDelete answer without running anything`() {
        assertEquals("data-system", ProtectedRoot.checkWrite("android-root-cli", "s", "/metadata/x")?.category?.label)
        assertNull(ProtectedRoot.checkWrite("android-root-cli", "s", "/sdcard/x"))
        assertEquals("data-system", ProtectedRoot.checkDelete("android-root-cli", "s", "/data/data")?.category?.label)
        assertTrue(scripts.isEmpty())
        enabled = false
        assertNull(ProtectedRoot.checkWrite("android-root-cli", "s", "/metadata/x"))
    }

    @Test fun `a refusal result is not a success`() {
        val r = ProtectedRoot.refusalResult(DeviceIntegrityPolicy.Denial(DeviceIntegrityPolicy.Category.PARTITION, "x"))
        assertFalse(r.success)
    }
}
