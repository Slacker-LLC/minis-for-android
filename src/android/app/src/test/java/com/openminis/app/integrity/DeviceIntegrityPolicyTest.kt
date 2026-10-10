package com.openminis.app.integrity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceIntegrityPolicyTest {
    private val home = "com.example.launcher"
    private val ime = "com.example.keyboard"
    private val policy = DeviceIntegrityPolicy(object : CorePackageResolver {
        override fun isCore(packageName: String) =
            CorePackageRules.isStaticCore(packageName) || packageName == home || packageName == ime
    })

    private fun argv(vararg w: String) = policy.checkArgv(w.first(), w.drop(1))
    private fun script(s: String) = policy.checkScript(s)

    private fun assertDenied(d: DeviceIntegrityPolicy.Denial?, category: DeviceIntegrityPolicy.Category) {
        assertNotNull("expected DEVICE_PROTECTED", d)
        assertEquals(category, d!!.category)
        check(d.message.startsWith("DEVICE_PROTECTED: ${category.label}: "))
    }

    // ---- root.shell (argv) denials ----

    @Test fun `dd to a block device is refused`() =
        assertDenied(argv("dd", "if=/dev/zero", "of=/dev/block/by-name/boot"), DeviceIntegrityPolicy.Category.PARTITION)

    @Test fun `rm of data system is refused`() =
        assertDenied(argv("rm", "-rf", "/data/system"), DeviceIntegrityPolicy.Category.DATA_SYSTEM)

    @Test fun `remount of system is refused`() =
        assertDenied(argv("mount", "-o", "remount,rw", "/system"), DeviceIntegrityPolicy.Category.SYSTEM_PARTITION)

    @Test fun `uninstalling systemui is refused`() =
        assertDenied(argv("pm", "uninstall", "com.android.systemui"), DeviceIntegrityPolicy.Category.CORE_PACKAGE)

    @Test fun `clearing provisioning is refused`() {
        assertDenied(argv("settings", "put", "global", "device_provisioned", "0"), DeviceIntegrityPolicy.Category.DEVICE_STATE)
        assertDenied(argv("settings", "put", "secure", "user_setup_complete", "0"), DeviceIntegrityPolicy.Category.DEVICE_STATE)
        assertDenied(argv("settings", "delete", "global", "device_provisioned"), DeviceIntegrityPolicy.Category.DEVICE_STATE)
    }

    @Test fun `provisioning set to one is fine`() =
        assertNull(argv("settings", "put", "global", "device_provisioned", "1"))

    // ---- exec (script) denials ----

    @Test fun `exec rm metadata glob is refused`() =
        assertDenied(script("rm -rf /metadata/*"), DeviceIntegrityPolicy.Category.DATA_SYSTEM)

    @Test fun `exec redirect into a block device is refused`() =
        assertDenied(script("echo x > /dev/block/sda"), DeviceIntegrityPolicy.Category.PARTITION)

    @Test fun `exec through sh -c is followed`() =
        assertDenied(script("sh -c 'rm -rf /data/system'"), DeviceIntegrityPolicy.Category.DATA_SYSTEM)

    @Test fun `exec through su -c and env is followed`() {
        assertDenied(script("su -c 'rm -rf /data/misc'"), DeviceIntegrityPolicy.Category.DATA_SYSTEM)
        assertDenied(script("env FOO=1 rm -rf /data/vendor"), DeviceIntegrityPolicy.Category.DATA_SYSTEM)
        assertDenied(script("busybox rm -rf /data/system"), DeviceIntegrityPolicy.Category.DATA_SYSTEM)
    }

    @Test fun `exec chained commands each get checked`() =
        assertDenied(script("ls /sdcard && rm -rf /data/system_ce; echo done"), DeviceIntegrityPolicy.Category.DATA_SYSTEM)

    @Test fun `exec find delete and exec are followed`() {
        assertDenied(script("find /data/system -delete"), DeviceIntegrityPolicy.Category.DATA_SYSTEM)
        assertDenied(script("find /data/misc -name '*.x' -exec rm {} ;"), DeviceIntegrityPolicy.Category.DATA_SYSTEM)
    }

    @Test fun `cd then relative delete is resolved`() =
        assertDenied(script("cd /data/system && rm -rf *"), DeviceIntegrityPolicy.Category.DATA_SYSTEM)

    @Test fun `file rm -r of data adb modules is refused`() =
        assertDenied(policy.checkDeletePath("/data/adb/modules"), DeviceIntegrityPolicy.Category.DATA_SYSTEM)

    // ---- packages and device state ----

    @Test fun `disabling the current launcher is refused`() =
        assertDenied(argv("pm", "disable-user", "--user", "0", home), DeviceIntegrityPolicy.Category.CORE_PACKAGE)

    @Test fun `package operations through cmd package are refused`() {
        assertDenied(argv("cmd", "package", "uninstall", "-k", "--user", "0", ime), DeviceIntegrityPolicy.Category.CORE_PACKAGE)
        assertDenied(argv("pm", "clear", "com.android.settings"), DeviceIntegrityPolicy.Category.CORE_PACKAGE)
        assertDenied(argv("pm", "suspend", "com.android.systemui"), DeviceIntegrityPolicy.Category.CORE_PACKAGE)
        assertDenied(argv("pm", "hide", "android"), DeviceIntegrityPolicy.Category.CORE_PACKAGE)
    }

    @Test fun `component names reduce to their package`() =
        assertDenied(argv("pm", "disable", "com.android.systemui/.SystemUIService"), DeviceIntegrityPolicy.Category.CORE_PACKAGE)

    @Test fun `removing user zero is refused`() {
        assertDenied(argv("pm", "remove-user", "0"), DeviceIntegrityPolicy.Category.DEVICE_STATE)
        assertDenied(argv("cmd", "user", "remove", "0"), DeviceIntegrityPolicy.Category.DEVICE_STATE)
    }

    @Test fun `removing another user is fine`() = assertNull(argv("pm", "remove-user", "10"))

    @Test fun `factory reset broadcasts and recovery wipe are refused`() {
        assertDenied(argv("am", "broadcast", "-a", "android.intent.action.FACTORY_RESET"), DeviceIntegrityPolicy.Category.DEVICE_STATE)
        assertDenied(argv("am", "broadcast", "-a", "android.intent.action.MASTER_CLEAR"), DeviceIntegrityPolicy.Category.DEVICE_STATE)
        assertDenied(argv("recovery", "--wipe_data"), DeviceIntegrityPolicy.Category.DEVICE_STATE)
        assertDenied(script("echo --wipe_data > /cache/recovery/command"), DeviceIntegrityPolicy.Category.DEVICE_STATE)
    }

    // ---- allowed ----

    @Test fun `ordinary app data and sdcard work stay open`() {
        assertNull(argv("rm", "-rf", "/data/data/com.example.app/cache"))
        assertNull(script("rm -rf /data/data/com.example.app/cache/*"))
        assertNull(script("rm -rf /sdcard/Download/*"))
        assertNull(argv("cat", "/proc/1234/maps"))
        assertNull(argv("kill", "-9", "1"))
        assertNull(script("echo hi > /data/local/tmp/x && chmod 755 /data/local/tmp/x"))
    }

    @Test fun `non core preinstalled apps can be uninstalled for user zero`() =
        assertNull(argv("pm", "uninstall", "--user", "0", "com.example.bloat"))

    @Test fun `reading a partition into the sdcard is allowed`() {
        assertNull(argv("dd", "if=/dev/block/by-name/boot", "of=/sdcard/boot.img"))
        assertNull(script("dd if=/dev/block/by-name/boot of=/sdcard/boot.img bs=1M"))
    }

    @Test fun `data adb minis stays writable`() {
        assertNull(policy.checkWritePath("/data/adb/minis/rootfs/x"))
        assertNull(argv("rm", "-rf", "/data/adb/minis/runtime/old"))
    }

    @Test fun `the user protected top level nodes cannot be removed but can be written under`() {
        assertDenied(argv("rm", "-rf", "/data/data"), DeviceIntegrityPolicy.Category.DATA_SYSTEM)
        assertDenied(argv("rm", "-rf", "/data"), DeviceIntegrityPolicy.Category.DATA_SYSTEM)
        assertDenied(argv("mv", "/data/media", "/sdcard/x"), DeviceIntegrityPolicy.Category.DATA_SYSTEM)
        assertDenied(script("rm -rf /data/*"), DeviceIntegrityPolicy.Category.DATA_SYSTEM)
        assertNotNull(script("rm -rf /*"))
        assertNull(argv("mkdir", "-p", "/data/local/tmp/a"))
    }

    // ---- path tricks ----

    @Test fun `case dot dotdot double slash and proc root do not hide a path`() {
        assertDenied(argv("rm", "-rf", "/DATA/System"), DeviceIntegrityPolicy.Category.DATA_SYSTEM)
        assertDenied(argv("rm", "-rf", "/data/./system"), DeviceIntegrityPolicy.Category.DATA_SYSTEM)
        assertDenied(argv("rm", "-rf", "/data/local/../system"), DeviceIntegrityPolicy.Category.DATA_SYSTEM)
        assertDenied(argv("rm", "-rf", "//data//system"), DeviceIntegrityPolicy.Category.DATA_SYSTEM)
        assertDenied(argv("rm", "-rf", "/proc/self/root/data/system"), DeviceIntegrityPolicy.Category.DATA_SYSTEM)
        assertDenied(argv("rm", "-rf", "/proc/1/root/metadata"), DeviceIntegrityPolicy.Category.DATA_SYSTEM)
    }

    @Test fun `normalize collapses the usual tricks`() {
        assertEquals("/data/system", DeviceIntegrityPolicy.normalize("//data/./x/../system/"))
        assertEquals("/", DeviceIntegrityPolicy.normalize("/../.."))
    }

    // ---- core package rules ----

    @Test fun `core package facts`() {
        assertNull(null)
        check(CorePackageRules.isCore(CorePackageRules.Facts("x.y", persistent = true)))
        check(CorePackageRules.isCore(CorePackageRules.Facts("x.y", sharedUserId = "android.uid.system")))
        check(CorePackageRules.isCore(CorePackageRules.Facts("x.y", sharedUserId = "android.uid.phone")))
        check(CorePackageRules.isCore(CorePackageRules.Facts("x.y", isCurrentHome = true)))
        check(CorePackageRules.isCore(CorePackageRules.Facts("x.y", isDefaultInputMethod = true)))
        check(!CorePackageRules.isCore(CorePackageRules.Facts("com.example.bloat")))
    }

    // ---- shell splitting ----

    @Test fun `shell words keep quotes and split commands`() {
        val c = ShellWords.split("a 'b c' \"d e\" > /x 2>/dev/null; f && g | h")
        assertEquals(listOf("a", "b c", "d e"), c[0].words)
        assertEquals(listOf("/x", "/dev/null"), c[0].redirects)
        assertEquals(listOf("f"), c[1].words)
        assertEquals(listOf("g"), c[2].words)
        assertEquals(listOf("h"), c[3].words)
    }
}
