package com.openminis.app.scheduled

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledReadOnlyPolicyTest {
    private fun allowed(command: String) = ScheduledReadOnlyPolicy.evaluateShell(command).allowed

    @Test
    fun `every documented read-only command is allowed`() {
        val commands = listOf(
            "ls -la", "cat notes.txt", "head -n 3 notes.txt", "tail -n 3 notes.txt",
            "wc -l notes.txt", "grep -n word notes.txt", "sort notes.txt", "uniq notes.txt",
            "cut -d, -f1 notes.csv", "tr a-z A-Z", "date -u", "pwd", "whoami", "hostname",
            "uname -a", "uptime", "df -h", "du -sh /workspace", "ps aux", "stat notes.txt",
            "file notes.txt", "echo hello",
            "git status --short", "git log -1", "git diff", "git show HEAD",
            "git branch", "git ls-files", "git rev-parse HEAD",
            "find . -type f", "sed --sandbox -n '1,3p' notes.txt",
        )
        commands.forEach { command -> assertTrue("should allow: $command", allowed(command)) }
    }

    @Test
    fun `unlisted execution and write commands are rejected`() {
        val commands = listOf(
            "sh -c 'echo unsafe'", "bash -c 'echo unsafe'", "eval true", "xargs echo",
            "env", "awk '{print}' file", "python3 -c 'print(1)'", "perl -e 1", "node -e 1",
            "curl https://example.test", "wget https://example.test", "ssh host", "scp a host:b",
            "nc -l 1234", "git push", "git commit -m x", "git checkout main", "git reset --hard",
            "rm -rf /", "mv a b", "cp a b", "chmod 777 a", "pip install x", "apt update", "npm install",
            "minis-scheduled list", "android-shizuku-cli status", "git branch new-branch",
            "git branch -D old-branch", "git diff --ext-diff", "git show --textconv", "git status && ls",
            "git status --help", "git log --show-signature",
        )
        commands.forEach { command -> assertFalse("should reject: $command", allowed(command)) }
    }

    @Test
    fun `segments are evaluated independently and any rejected segment denies the whole command`() {
        val decision = ScheduledReadOnlyPolicy.evaluateShell("ls && rm -rf /")
        assertFalse(decision.allowed)
        assertEquals(2, decision.segments.size)
        assertTrue(decision.segments[0].allowed)
        assertFalse(decision.segments[1].allowed)
        assertNotNull(decision.rejectedSegment)

        val pipeline = ScheduledReadOnlyPolicy.evaluateShell("cat notes.txt | sh")
        assertFalse(pipeline.allowed)
        assertTrue(pipeline.segments.first().allowed)
        assertFalse(pipeline.segments.last().allowed)
    }

    @Test
    fun `shell substitution process substitution heredoc and unsupported quoting fail closed`() {
        listOf(
            "echo \$(rm x)", "echo `rm x`", "cat <(cat secret)", "cat <<EOF\nsecret\nEOF",
            "echo 'unterminated", "ls \\; rm -rf /",
        ).forEach { command -> assertFalse("should reject: $command", allowed(command)) }
    }

    @Test
    fun `output redirection is confined to the per-session temporary directory`() {
        assertTrue(allowed("echo note > /tmp/note.txt"))
        assertTrue(allowed("echo note >> /var/minis/offloads/note.txt"))
        assertTrue(ScheduledReadOnlyPolicy.isTemporaryPath("/tmp/nested/../note.txt"))
        assertTrue(ScheduledReadOnlyPolicy.isTemporaryPath("/var/minis/offloads/note.txt"))
        assertEquals(
            "/var/minis/offloads/note.txt",
            ScheduledReadOnlyPolicy.canonicalTemporaryPath("/tmp/note.txt"),
        )
        listOf(
            "echo note > /etc/x", "echo note > /workspace/x", "echo note > /var/minis/offloads-evil/x",
            "echo note > relative.txt", "echo note > /tmp/../../etc/x", "echo note >",
        ).forEach { command -> assertFalse("should reject: $command", allowed(command)) }
    }

    @Test
    fun `find sed git date and sort side effect options are rejected`() {
        listOf(
            "find . -exec rm {} \\;", "find . -delete", "find . -ok rm {} \\;",
            "sed -n '1,3p' file", "sed -i 's/a/b/' file", "sed --sandbox --in-place file",
            "git branch -D main", "git diff --output=/etc/x", "date --set='2020-01-01'",
            "sort -o /etc/x file", "sort --compress-program=sh file",
            "sort -T /etc file", "sort -T /tmp file", "sort --temporary-directory=/etc file",
        ).forEach { command -> assertFalse("should reject: $command", allowed(command)) }
    }

    @Test
    fun `uniq with an output operand is rejected but one input file is fine`() {
        listOf(
            "uniq in.txt out.txt", "uniq -c in.txt /workspace/out.txt", "uniq -f 1 in.txt out.txt",
            "uniq -- in.txt out.txt", "uniq - out.txt extra",
        ).forEach { command -> assertFalse("should reject: $command", allowed(command)) }
        listOf("uniq in.txt", "uniq -c in.txt", "uniq -f 1 in.txt", "uniq -s 2 -w 5 in.txt", "sort in.txt | uniq -c")
            .forEach { command -> assertTrue("should allow: $command", allowed(command)) }
    }

    @Test
    fun `read-only git invocation disables repository-configured executables`() {
        val hardened = ScheduledReadOnlyPolicy.hardenGitInvocation("git diff --stat")
        assertTrue(hardened.startsWith("GIT_CONFIG_NOSYSTEM=1"))
        assertTrue(hardened.contains("core.fsmonitor=false"))
        assertTrue(hardened.contains("diff.external="))
        assertTrue(hardened.contains("--no-textconv"))
        val redirected = ScheduledReadOnlyPolicy.hardenGitInvocation("git show HEAD > '/tmp/git output.txt'")
        assertTrue(redirected.contains(" > '/tmp/git output.txt'"))
        assertEquals("ls -la", ScheduledReadOnlyPolicy.hardenGitInvocation("ls -la"))
    }

    @Test
    fun `empty overlong control and unicode-confusable commands are rejected`() {
        assertFalse(allowed(""))
        assertFalse(allowed("   "))
        assertFalse(allowed("ls;"))
        assertFalse(allowed("x".repeat(ScheduledReadOnlyPolicy.MAX_SHELL_COMMAND_CHARS + 1)))
        assertFalse(allowed("ls\u0000"))
        assertFalse(allowed("ls\u001b[31m"))
        assertFalse(allowed("ls；rm -rf /"))
        assertFalse(allowed("ls	&& cat file"))
    }

    @Test
    fun `file tools may write only to the session temp area and read tools remain available`() {
        assertNull(ScheduledReadOnlyPolicy.fileWriteDenial("linux.file.write", JSONObject().put("path", "/tmp/draft.txt")))
        assertNull(ScheduledReadOnlyPolicy.fileWriteDenial("linux.file.append", JSONObject().put("path", "/var/minis/offloads/draft.txt")))
        assertNull(ScheduledReadOnlyPolicy.fileWriteDenial("linux.file.copy", JSONObject().put("source", "/workspace/readme").put("destination", "/tmp/readme")))
        assertNotNull(ScheduledReadOnlyPolicy.fileWriteDenial("linux.file.write", JSONObject().put("path", "/workspace/result.txt")))
        assertNotNull(ScheduledReadOnlyPolicy.fileWriteDenial("linux.file.write", JSONObject().put("path", "relative.txt")))
        assertEquals(
            "linux.file.write: /workspace/bad path",
            ScheduledReadOnlyPolicy.fileWriteSummary(
                "linux.file.write", JSONObject().put("path", "/workspace/bad\npath\u001b"),
            ),
        )
        assertNotNull(ScheduledReadOnlyPolicy.fileWriteDenial("linux.file.move", JSONObject().put("source", "/workspace/a").put("destination", "/tmp/a")))
        assertNotNull(ScheduledReadOnlyPolicy.fileWriteDenial("linux.file.delete", JSONObject().put("path", "/tmp/../../etc/passwd")))
        assertNotNull(ScheduledReadOnlyPolicy.fileWriteDenial("memory_write", JSONObject().put("content", "note")))
        assertNull(ScheduledReadOnlyPolicy.fileWriteDenial("linux.file.read", JSONObject().put("path", "/workspace/readme")))
    }

    @Test
    fun `arbitrary code and package installation tools are denied`() {
        assertNotNull(ScheduledReadOnlyPolicy.codeExecutionDenial("linux.python.run"))
        assertNotNull(ScheduledReadOnlyPolicy.codeExecutionDenial("linux.pip.install"))
        assertNull(ScheduledReadOnlyPolicy.codeExecutionDenial("android.web.search"))
    }
}

class ScheduledTaskPermissionTierJsonTest {
    private fun task() = ScheduledTask(
        id = "routine-1",
        label = "Daily check",
        timeOfDayHour = 9,
        timeOfDayMinute = 0,
        repeatMode = ScheduledRepeatMode.DAILY,
        prompt = "Check status",
    )

    @Test
    fun `new tasks default to read-only and round-trip their tier`() {
        val task = task()
        assertEquals(ScheduledTaskPermissionTier.READ_ONLY, task.permissionTier)
        assertEquals(ScheduledTaskPermissionTier.READ_ONLY, ScheduledTask.fromJson(task.toJson()).permissionTier)
        assertEquals("READ_ONLY", task.toJson().getString("permissionTier"))
    }

    @Test
    fun `legacy JSON without tier preserves historical full access`() {
        val legacy = JSONObject(task().toJson().toString()).apply { remove("permissionTier") }
        assertEquals(ScheduledTaskPermissionTier.FULL, ScheduledTask.fromJson(legacy).permissionTier)
        assertEquals(ScheduledTaskPermissionTier.FULL, ScheduledTaskPermissionTier.fromPersistedJson(legacy))
    }

    @Test
    fun `malformed or unknown persisted tier fails closed to read-only`() {
        val base = task().toJson()
        val malformed = listOf(
            JSONObject(base.toString()).put("permissionTier", 1),
            JSONObject(base.toString()).put("permissionTier", JSONObject.NULL),
            JSONObject(base.toString()).put("permissionTier", "ADMIN"),
            JSONObject(base.toString()).put("permissionTier", ""),
        )
        malformed.forEach { json ->
            assertEquals(ScheduledTaskPermissionTier.READ_ONLY, ScheduledTask.fromJson(json).permissionTier)
        }
    }
}
