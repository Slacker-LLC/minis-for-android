package com.openminis.app.runtime.minisd

import org.json.JSONObject

/** Pure helpers for materializing the app-scoped minisd policy and watchdog command. */
internal object MinisdBootstrap {
    const val POLICY_ASSET = "minisd-policy.json"
    const val POLICY_PATH = "/data/adb/minis/policy/policy.json"
    internal const val PERSISTENT_MIGRATION_MARKER = "/data/adb/minis/.android-persistent-v1"
    private const val POLICY_DIR = "/data/adb/minis/policy"
    private const val PID_FILE = "/data/adb/minis/run/minisd.pid"
    private const val ROOT_SOCKET = "/data/adb/minis/run/minisd.sock"

    fun policyForUid(template: String, appUid: Int): String {
        require(appUid > 0) { "appUid must be > 0" }
        val root = JSONObject(template)
        require(root.optJSONObject("methods") != null) { "minisd policy is missing methods" }
        val caller = root.optJSONObject("caller") ?: JSONObject().also { root.put("caller", it) }
        caller.put("appUid", appUid)
        return root.toString()
    }

    /**
     * Starts the privileged broker independently of Ubuntu rootfs health.
     * Rootfs validation belongs to the runtime recovery state machine after
     * the broker is reachable; otherwise a missing rootfs deadlocks recovery.
     *
     * Before spawning the broker, root prepares the fixed persistent data roots,
     * migrates legacy app-private data once, and applies the exact SELinux
     * app-data label (including MLS categories) copied from the live filesDir.
     */
    fun watchdogCommand(
        appSocket: String,
        policyJson: String,
        forceRestart: Boolean,
    ): String {
        val caller = JSONObject(policyJson).optJSONObject("caller")
        val appUid = caller?.optInt("appUid", 0) ?: 0
        require(appUid > 0) { "policy caller.appUid must be > 0" }
        val appFiles = java.io.File(appSocket).parentFile?.parentFile?.absolutePath
            ?: throw IllegalArgumentException("appSocket must be under <filesDir>/minis")

        val commands = mutableListOf<String>()
        commands += "BIN=${shellQuote(MinisdProtocol.DEFAULT_BIN)}"
        commands += "POLICY=${shellQuote(POLICY_PATH)}"
        commands += "APP_SOCKET=${shellQuote(appSocket)}"
        commands += "PIDFILE=${shellQuote(PID_FILE)}"
        commands += "if [ ! -x \"\$BIN\" ]; then echo \"minisd missing or not executable: \$BIN\" >&2; exit 40; fi"
        commands += persistentDataPreparationCommand(appFiles, appUid)
        commands += "mkdir -p ${shellQuote(POLICY_DIR)} || { echo \"cannot create minisd policy directory\" >&2; exit 43; }"
        commands += "umask 077"
        commands += "printf '%s' ${shellQuote(policyJson)} > \"\$POLICY.tmp\" || { echo \"cannot write minisd policy\" >&2; exit 44; }"
        commands += "mv -f \"\$POLICY.tmp\" \"\$POLICY\" || { echo \"cannot install minisd policy\" >&2; exit 45; }"

        // A syntactically invalid pidfile, or one naming a process that no
        // longer exists, is stale state and can be removed without killing
        // anything. Never trust a numeric pid until its cmdline is verified.
        commands += "pid=\"\""
        commands += "if [ -r \"\$PIDFILE\" ]; then pid=\$(cat \"\$PIDFILE\" 2>/dev/null || true); fi"
        commands += "case \"\$pid\" in ''|*[!0-9]*) [ -e \"\$PIDFILE\" ] && rm -f \"\$PIDFILE\" ;; *) [ -d \"/proc/\$pid\" ] || { rm -f \"\$PIDFILE\"; pid=\"\"; } ;; esac"

        if (forceRestart) {
            commands += "case \"\$pid\" in ''|*[!0-9]*) ;; *) child_cmd=\$(tr '\\000' ' ' < \"/proc/\$pid/cmdline\" 2>/dev/null || true); case \"\$child_cmd\" in *minisd*--socket*/data/adb/minis/run/minisd.sock*) ppid=\$(awk '/^PPid:/{print \$2; exit}' \"/proc/\$pid/status\" 2>/dev/null || true); case \"\$ppid\" in ''|*[!0-9]*) ;; *) parent_cmd=\$(tr '\\000' ' ' < \"/proc/\$ppid/cmdline\" 2>/dev/null || true); case \"\$parent_cmd\" in *minisd*--watchdog*) kill \"\$ppid\" 2>/dev/null || true; kill \"\$pid\" 2>/dev/null || true ;; esac ;; esac ;; esac ;; esac"
            commands += "rm -f \"\$PIDFILE\""
            commands += "sleep 1"
        }

        commands += "(\"\$BIN\" --watchdog --policy \"\$POLICY\" --app-socket \"\$APP_SOCKET\" >/dev/null 2>&1 &)"
        commands += "echo \"minisd watchdog spawn requested\""
        return commands.joinToString("\n")
    }

    /**
     * Quiesce the broker before replacing its executable. A live pid is never
     * trusted solely because it appears in the pidfile: both child and parent
     * command lines must identify the expected minisd server/watchdog lineage.
     * Unknown live owners fail closed instead of being killed.
     */
    internal fun runtimeSwitchShutdownCommand(appSocket: String): String {
        val commands = mutableListOf<String>()
        commands += "BIN=${shellQuote(MinisdProtocol.DEFAULT_BIN)}"
        commands += "PIDFILE=${shellQuote(PID_FILE)}"
        commands += "ROOT_SOCKET=${shellQuote(ROOT_SOCKET)}"
        commands += "APP_SOCKET=${shellQuote(appSocket)}"
        commands += "pid=\"\""
        commands += "if [ -r \"\$PIDFILE\" ]; then pid=\$(cat \"\$PIDFILE\" 2>/dev/null || true); fi"
        commands += "case \"\$pid\" in ''|*[!0-9]*) [ -e \"\$PIDFILE\" ] && rm -f \"\$PIDFILE\"; pid=\"\" ;; *) [ -d \"/proc/\$pid\" ] || { rm -f \"\$PIDFILE\"; pid=\"\"; } ;; esac"
        commands += "if [ -n \"\$pid\" ]; then child_cmd=\$(tr '\\000' ' ' < \"/proc/\$pid/cmdline\" 2>/dev/null || true); case \"\$child_cmd\" in *minisd*--socket*/data/adb/minis/run/minisd.sock*) ;; *) echo \"refusing to kill unrecognized pidfile owner pid=\$pid\" >&2; exit 46 ;; esac; ppid=\$(awk '/^PPid:/{print \$2; exit}' \"/proc/\$pid/status\" 2>/dev/null || true); case \"\$ppid\" in ''|*[!0-9]*) echo \"minisd watchdog parent unavailable for pid=\$pid\" >&2; exit 47 ;; esac; parent_cmd=\$(tr '\\000' ' ' < \"/proc/\$ppid/cmdline\" 2>/dev/null || true); case \"\$parent_cmd\" in *minisd*--watchdog*) ;; *) echo \"refusing runtime switch: unrecognized minisd parent pid=\$ppid\" >&2; exit 48 ;; esac; kill \"\$ppid\" 2>/dev/null || true; kill \"\$pid\" 2>/dev/null || true; i=0; while [ -d \"/proc/\$pid\" ] && [ \"\$i\" -lt 20 ]; do sleep 0.1; i=\$((i+1)); done; [ -d \"/proc/\$pid\" ] && { echo \"minisd did not stop\" >&2; exit 49; }; fi"
        commands += "rm -f \"\$PIDFILE\" \"\$ROOT_SOCKET\" \"\$APP_SOCKET\""
        return commands.joinToString("\n")
    }

    /**
     * Root-side, idempotent migration contract for Issue #50.
     *
     * The completion marker is written only after every copy, ownership pass,
     * and SELinux relabel succeeds. Re-running after an interrupted copy is safe
     * because runtime startup has not been allowed to proceed before this block.
     */
    internal fun persistentDataPreparationCommand(appFilesDir: String, appUid: Int): String {
        require(appUid > 0) { "appUid must be > 0" }
        require(appFilesDir.startsWith('/')) { "appFilesDir must be absolute" }
        val qFiles = shellQuote(appFilesDir)
        val qMarker = shellQuote(PERSISTENT_MIGRATION_MARKER)
        return listOf(
            "ROOT='/data/adb/minis'",
            "APP_FILES=$qFiles",
            "APP_UID='$appUid'",
            "MIGRATION_MARKER=$qMarker",
            "mkdir -p \"\$ROOT/workspace\" \"\$ROOT/sessions\" \"\$ROOT/memory\" \"\$ROOT/skills\" \"\$ROOT/shared\" \"\$ROOT/home\" || { echo 'cannot create persistent Minis data roots' >&2; exit 50; }",
            "for d in \"\$ROOT/workspace\" \"\$ROOT/sessions\" \"\$ROOT/memory\" \"\$ROOT/skills\" \"\$ROOT/shared\" \"\$ROOT/home\"; do chown \"\$APP_UID:\$APP_UID\" \"\$d\" || exit 51; chmod 0700 \"\$d\" || exit 52; done",
            "copy_tree() { src=\"\$1\"; dst=\"\$2\"; if [ -d \"\$src\" ] && [ ! -L \"\$src\" ]; then cp -a \"\$src/.\" \"\$dst/\" || { echo \"legacy migration failed: \$src\" >&2; exit 53; }; fi; }",
            "if [ ! -e \"\$MIGRATION_MARKER\" ]; then copy_tree \"\$APP_FILES/minis/workspace\" \"\$ROOT/workspace\"; copy_tree \"\$APP_FILES/minis-sessions\" \"\$ROOT/sessions\"; copy_tree \"\$APP_FILES/minis-global/memory\" \"\$ROOT/memory\"; copy_tree \"\$APP_FILES/minis-global/skills\" \"\$ROOT/skills\"; copy_tree \"\$APP_FILES/minis-global/shared\" \"\$ROOT/shared\"; copy_tree \"\$APP_FILES/minis-global/home\" \"\$ROOT/home\"; copy_tree \"\$ROOT/rootfs/home/minis\" \"\$ROOT/home\"; chown -R \"\$APP_UID:\$APP_UID\" \"\$ROOT/workspace\" \"\$ROOT/sessions\" \"\$ROOT/memory\" \"\$ROOT/skills\" \"\$ROOT/shared\" \"\$ROOT/home\" || exit 54; find \"\$ROOT/workspace\" \"\$ROOT/sessions\" \"\$ROOT/memory\" \"\$ROOT/skills\" \"\$ROOT/shared\" \"\$ROOT/home\" -type d -exec chmod 0700 {} + || exit 54; find \"\$ROOT/workspace\" \"\$ROOT/sessions\" \"\$ROOT/memory\" \"\$ROOT/skills\" \"\$ROOT/shared\" \"\$ROOT/home\" -type f -exec chmod u+rw,go-rwx {} + || exit 54; touch \"\$MIGRATION_MARKER\" || exit 54; fi",
            "command -v restorecon >/dev/null 2>&1 && restorecon -RF \"\$APP_FILES\" >/dev/null 2>&1 || true",
            "command -v chcon >/dev/null 2>&1 || { echo 'SELinux relabel unavailable: chcon not found' >&2; exit 55; }",
            "APP_LABEL=\$(ls -Zd \"\$APP_FILES\" 2>/dev/null | awk '{print \$1; exit}')",
            "case \"\$APP_LABEL\" in u:object_r:app_data_file:s0:*) ;; *) echo \"unexpected filesDir SELinux label: \$APP_LABEL\" >&2; exit 56 ;; esac",
            "for d in \"\$ROOT/workspace\" \"\$ROOT/sessions\" \"\$ROOT/memory\" \"\$ROOT/skills\" \"\$ROOT/shared\" \"\$ROOT/home\"; do chcon -hR \"\$APP_LABEL\" \"\$d\" || { echo \"cannot relabel \$d\" >&2; exit 57; }; done",
            "ACTUAL_LABEL=\$(ls -Zd \"\$ROOT/memory\" 2>/dev/null | awk '{print \$1; exit}')",
            "[ \"\$ACTUAL_LABEL\" = \"\$APP_LABEL\" ] || { echo \"persistent SELinux label mismatch: got \$ACTUAL_LABEL expected \$APP_LABEL\" >&2; exit 58; }",
            "chown \"\$APP_UID:\$APP_UID\" \"\$MIGRATION_MARKER\" 2>/dev/null || true",
            "chmod 0600 \"\$MIGRATION_MARKER\" 2>/dev/null || true",
        ).joinToString("\n")
    }

    internal fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    /** Extracts an exact numeric uid line while tolerating su diagnostics. */
    internal fun parseEffectiveUid(output: String): Int? = output
        .lineSequence()
        .map { it.trim() }
        .firstNotNullOfOrNull { line -> line.toIntOrNull() }
}
