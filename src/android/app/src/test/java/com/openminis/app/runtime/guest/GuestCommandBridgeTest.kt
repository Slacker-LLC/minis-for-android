package com.openminis.app.runtime.guest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestCommandBridgeTest {
    @Test
    fun `generated bridge wrapper keeps protocol newlines real`() {
        val script = GuestCommandBridge.wrapperScriptForTest()

        assertTrue(script.contains("MINISCFG3\\n%s\\n%s\\n%s\\n"))
        assertTrue(script.contains("tr -d ' \\n'"))
        assertFalse(script.contains("MINISCFG3\\\\n%s\\\\n"))
    }

    @Test
    fun `install script stays far below the per-argument limit however many commands there are`() {
        // The script travels as one `su -c` argument (kernel limit 128 KiB). It once carried a full wrapper copy
        // per command and the on-device install died with E2BIG.
        val wrapper = GuestCommandBridge.wrapperScriptForTest()
        val names = (1..300).map { "cmd-$it" }.toSet()

        val script = GuestCommandBridge.installScript("/rootfs", 10450, 10450, "cfg", wrapper, wrapper, names)

        assertTrue("script is ${script.length} chars", script.length < 90_000)
        // config, minis-config wrapper, first bridge wrapper, first URL wrapper: the rest are copies.
        assertEquals(4, Regex("(?m)^printf %s ").findAll(script).count())
    }

    @Test
    fun `install script removes a retired wrapper but never a symlink or a command still provided`() {
        val wrapper = GuestCommandBridge.wrapperScriptForTest()
        val retired = "/rootfs/usr/local/bin/android-shizuku-cli"

        val script = GuestCommandBridge.installScript("/rootfs", 10450, 10450, "cfg", wrapper, wrapper, setOf("android-root-cli"))

        assertTrue(script.contains("if [ -f '$retired' ] && [ ! -L '$retired' ]; then rm -f -- '$retired'; fi"))
        assertFalse(Regex("""(?m)^rm -f -- '$retired'""").containsMatchIn(script))

        val stillProvided = GuestCommandBridge.installScript(
            "/rootfs", 10450, 10450, "cfg", wrapper, wrapper, setOf("android-shizuku-cli"),
        )
        assertFalse(stillProvided.contains("rm -f -- '$retired'"))
    }

    @Test
    fun `managed guest CLI set includes registered Android handlers and preview aliases`() {
        val names = GuestCommandBridge.managedCommandNames(
            setOf("android-device", "minis-scheduled", "unsafe/name"),
        )

        assertTrue(names.contains("minis-config"))
        assertTrue(names.contains("minis-model-use"))
        assertTrue(names.contains("android-device"))
        assertTrue(names.contains("minis-scheduled"))
        assertTrue(names.contains("minis-open"))
        assertTrue(names.contains("xdg-open"))
        assertFalse(names.contains("unsafe/name"))
    }

    @Test
    fun `registered Android handler names are accepted by the direct bridge`() {
        val result = GuestCommandBridge.dispatch(
            cmd = "android-device",
            args = listOf("info"),
            session = "",
            cwd = "/workspace",
            stdin = "",
            pid = 123,
        ) { name ->
            assertEquals("android-device", name)
            NativeOffloadHandler { NativeOffloadResult(0, "ok\n") }
        }

        assertEquals(0, result.exitCode)
        assertEquals("ok\n", result.output)
    }

    @Test
    fun `dispatch preserves stdin session cwd and shell-sensitive arguments`() {
        val prompt = "quote=\"x\"\n中文 $ ` \\ end\n"
        var received: NativeOffloadRequest? = null
        val result = GuestCommandBridge.dispatch(
            cmd = "minis-model-use",
            args = listOf("run", "--model", "image-model"),
            session = "session-42",
            cwd = "/workspace",
            stdin = prompt,
            pid = 321,
        ) { name ->
            assertEquals("minis-model-use", name)
            NativeOffloadHandler {
                received = it
                NativeOffloadResult(0, "ok")
            }
        }
        assertEquals(0, result.exitCode)
        val request = received!!
        assertEquals(321, request.pid)
        assertEquals("session-42", request.sessionId)
        assertEquals("session-42", request.env["MINIS_CHAT_SESSION_ID"])
        assertEquals("/workspace", request.cwd)
        assertEquals(prompt, request.stdin)
        assertEquals(listOf("minis-model-use", "run", "--model", "image-model"), request.argv)
    }

    @Test
    fun `file payload replaces file flag and path with exact content`() {
        val args = listOf("set", "soul.body", "--file", "/tmp/value.json", "--caption", "x")
        val rewritten = GuestCommandBridge.rewriteFileArgument(
            args,
            "\"line1\\nline2 $ ` \\\\\"\"".toByteArray(),
        )
        assertEquals(
            listOf("set", "soul.body", "\"line1\\nline2 $ ` \\\\\"\"", "--caption", "x"),
            rewritten,
        )
        assertEquals(args, GuestCommandBridge.rewriteFileArgument(args, null))
    }

    @Test
    fun `invalid command and session are rejected before handler lookup`() {
        assertThrows(IllegalArgumentException::class.java) {
            GuestCommandBridge.dispatch("su", listOf("-c", "id"), "", "/workspace", "", 1) { error("lookup") }
        }
        assertThrows(IllegalArgumentException::class.java) {
            GuestCommandBridge.dispatch("minis-model-use", emptyList(), "../other", "/workspace", "", 1) { error("lookup") }
        }
    }

    @Test
    fun `unregistered model handler fails explicitly`() {
        val result = GuestCommandBridge.dispatch(
            "minis-model-use",
            listOf("list"),
            "",
            "/workspace",
            "",
            1,
        ) { null }
        assertEquals(127, result.exitCode)
        assertEquals("minis-model-use handler not registered\n", result.output)
    }

    @Test
    fun `connection limiter rejects excess workers and recovers after release`() {
        val limiter = GuestBridgeConnectionLimiter(2)
        assertTrue(limiter.tryAcquire())
        assertTrue(limiter.tryAcquire())
        assertEquals(2, limiter.activeCount())
        assertFalse(limiter.tryAcquire())

        limiter.release()
        assertEquals(1, limiter.activeCount())
        assertTrue(limiter.tryAcquire())
        assertEquals(2, limiter.activeCount())

        limiter.release()
        limiter.release()
        assertEquals(0, limiter.activeCount())
    }

    @Test
    fun `help never waits for piped input, a real run still reads it`() {
        val dir = java.nio.file.Files.createTempDirectory("minis-wrapper").toFile()
        try {
            val config = java.io.File(dir, "proxy").apply {
                // Port 1 refuses the connection, so the wrapper ends right after the stdin step.
                writeText("MINIS_CONFIG_PROXY_PORT=1\nMINIS_CONFIG_PROXY_TOKEN=t\n")
            }
            val wrapper = java.io.File(dir, "minis-model-use").apply {
                writeText(GuestCommandBridge.wrapperScriptForTest().replace("/etc/minis/minis-config-proxy", config.path))
            }
            fun run(vararg args: String): Pair<Int, String> {
                val process = ProcessBuilder("bash", "-c", "yes | bash \"\$0\" \"\$@\"", wrapper.path, *args)
                    .redirectErrorStream(true).start()
                val text = process.inputStream.bufferedReader().readText()
                return process.waitFor() to text
            }
            org.junit.Assume.assumeTrue(java.io.File("/bin/bash").canExecute() || java.io.File("/usr/bin/bash").canExecute())

            // Endless input: reading it is what the 2 MiB guard would reject.
            val help = run("run", "--help")
            assertEquals(help.second, 127, help.first)
            assertTrue(help.second, help.second.contains("cannot connect"))
            val shortHelp = run("run", "-h")
            assertTrue(shortHelp.second, shortHelp.second.contains("cannot connect"))

            val real = run("run")
            assertEquals(real.second, 1, real.first)
            assertTrue(real.second, real.second.contains("stdin exceeds 2 MiB"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
