package com.openminis.app.runtime.ubuntu

import com.openminis.app.runtime.ubuntu.AptCommandPolicy.Parsed
import com.openminis.app.runtime.ubuntu.AptCommandPolicy.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AptCommandPolicyTest {
    private fun ok(vararg args: String) = (AptCommandPolicy.parse(args.toList()) as Parsed.Ok).request
    private fun refused(vararg args: String) = (AptCommandPolicy.parse(args.toList()) as Parsed.Refused).message

    @Test
    fun `update install and remove are the only commands`() {
        assertEquals(Request.Update, ok("update"))
        assertEquals(Request.Install(listOf("ffmpeg", "libstdc++6")), ok("install", "ffmpeg", "libstdc++6"))
        assertEquals(Request.Remove(listOf("cowsay")), ok("remove", "cowsay"))
        for (verb in listOf("purge", "upgrade", "dist-upgrade", "autoremove", "download", "source", "full-upgrade", "")) {
            assertTrue(verb, AptCommandPolicy.parse(listOf(verb, "x")) is Parsed.Refused)
        }
        assertTrue(AptCommandPolicy.parse(emptyList()) is Parsed.Refused)
    }

    @Test
    fun `a duplicate name is asked once`() {
        assertEquals(Request.Install(listOf("a1", "b2")), ok("install", "a1", "b2", "a1"))
    }

    @Test
    fun `anything that is not a plain package name is refused before it can reach apt or the shell`() {
        val hostile = listOf(
            "-y", "--allow-remove-essential", "-o", "Dir::Etc=/tmp", "pkg=1.0", "pkg/jammy-backports", "pkg:arm64",
            "../../etc/passwd", "/tmp/evil.deb", "./evil.deb", "a;reboot", "a b", "a\nb", "\$(id)", "`id`", "a&&b",
            "a|b", "a>b", "'q'", "\"q\"", "UPPER", "", " ", "*", "~", "a".repeat(64),
        )
        for (name in hostile) {
            assertTrue("'$name' must be refused", AptCommandPolicy.parse(listOf("install", name)) is Parsed.Refused)
            assertTrue("'$name' must be refused", AptCommandPolicy.parse(listOf("remove", name)) is Parsed.Refused)
        }
    }

    @Test
    fun `one bad name refuses the whole call and a long list is capped`() {
        assertTrue(AptCommandPolicy.parse(listOf("install", "ffmpeg", "--force-yes")) is Parsed.Refused)
        val many = (1..AptCommandPolicy.MAX_PACKAGES + 1).map { "p$it" }
        assertTrue(AptCommandPolicy.parse(listOf("install") + many) is Parsed.Refused)
        assertTrue(AptCommandPolicy.parse(listOf("install") + many.take(AptCommandPolicy.MAX_PACKAGES)) is Parsed.Ok)
        assertTrue(refused("update", "x").contains("no arguments"))
    }

    @Test
    fun `the install script names the packages once, takes no extra options and uses the proxy only when given`() {
        val plain = AptCommandPolicy.guestScript(Request.Install(listOf("ffmpeg", "git-lfs")), "")
        assertTrue(plain.contains("install -y --no-install-recommends ffmpeg git-lfs"))
        assertTrue("without the helper apt goes direct, ignoring any stale proxy file", plain.contains("Acquire::http::Proxy=DIRECT"))
        assertFalse(plain.contains("127.0.0.1"))
        assertTrue(plain.contains("APT::Sandbox::User=root"))
        val proxied = AptCommandPolicy.guestScript(Request.Update, "http://user:pw@127.0.0.1:18787")
        assertTrue(proxied.contains("Acquire::http::Proxy="))
        assertFalse(proxied.contains("DIRECT"))
    }

    @Test
    fun `the runtime's own packages are in the protected set`() {
        assertTrue(UbuntuProvisioner.BASE_PACKAGES.all { it in AptCommandPolicy.PROTECTED })
        assertTrue("apt" in AptCommandPolicy.PROTECTED && "dpkg" in AptCommandPolicy.PROTECTED)
    }

    // ── the removal guard, run for real with a stand-in apt-get ───────────────

    private fun runRemoval(simulation: String, vararg packages: String): Pair<Int, String> {
        val bash = listOf("/bin/bash", "/usr/bin/bash").firstOrNull { File(it).canExecute() }
        assumeTrue("no bash on this machine", bash != null)
        val dir = Files.createTempDirectory("aptstub").toFile()
        try {
            val log = File(dir, "calls.log")
            val stub = File(dir, "apt-get").apply {
                writeText(
                    "#!/bin/bash\n" +
                        "echo \"\$@\" >> ${log.absolutePath}\n" +
                        "case \" \$* \" in *' -s '*) cat <<'SIM'\n$simulation\nSIM\n;; esac\n",
                )
                setExecutable(true)
            }
            val script = AptCommandPolicy.guestScript(Request.Remove(packages.toList()), "")
                .replace("/usr/bin/apt-get", stub.absolutePath)
            val process = ProcessBuilder(bash, "-c", script).redirectErrorStream(true).start()
            val out = process.inputStream.bufferedReader().readText()
            val code = process.waitFor()
            return code to (out + "\n" + (if (log.exists()) log.readText() else ""))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a removal that only takes out what was asked for goes through`() {
        val (code, out) = runRemoval("Remv cowsay [3.03]\nRemv libcowsay [1]", "cowsay")
        assertEquals(out, 0, code)
        assertTrue(out, out.contains("remove -y cowsay"))
    }

    @Test
    fun `a removal that would also take out a runtime package is refused before anything is removed`() {
        val (code, out) = runRemoval("Remv libffi8 [3.4]\nRemv python3 [3.12]\nRemv git [1]", "libffi8")
        assertEquals(out, AptCommandPolicy.EXIT_PROTECTED, code)
        assertFalse("nothing was removed", out.contains("remove -y"))
        assertTrue(out, out.contains("refused"))
    }

    @Test
    fun `a package whose name only starts like a protected one is not blocked`() {
        val (code, _) = runRemoval("Remv python3-requests [1]\nRemv gitg [2]\nRemv aptitude [3]", "python3-requests")
        assertEquals(0, code)
    }

    @Test
    fun `a name with a plus or dot is matched literally`() {
        val (code, out) = runRemoval("Remv libstdc++6 [1]\nRemv ca-certificates [2]", "libstdc++6")
        assertEquals(out, AptCommandPolicy.EXIT_PROTECTED, code)
        val (ok, _) = runRemoval("Remv ca-certificatesX [2]\nRemv libstdcxx [1]", "ca-certificatesX")
        assertEquals(0, ok)
    }
}
