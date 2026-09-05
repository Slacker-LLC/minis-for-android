package com.openminis.app.runtime.minisd

import org.json.JSONObject

/** Pure helpers for materializing the app-scoped minisd policy and watchdog command. */
internal object MinisdBootstrap {
    const val POLICY_ASSET = "minisd-policy.json"
    const val POLICY_PATH = "/data/adb/minis/policy/policy.json"
    private const val POLICY_DIR = "/data/adb/minis/policy"
    private const val PID_FILE = "/data/adb/minis/run/minisd.pid"

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
     */
    fun watchdogCommand(
        appSocket: String,
        policyJson: String,
        forceRestart: Boolean,
        packagedBroker: String = "",
        appUid: Int = 0,
    ): String {
        val commands = mutableListOf<String>()
        commands += "BIN=${shellQuote(MinisdProtocol.DEFAULT_BIN)}"
        commands += "POLICY=${shellQuote(POLICY_PATH)}"
        commands += "APP_SOCKET=${shellQuote(appSocket)}"
        commands += "PIDFILE=${shellQuote(PID_FILE)}"
        if (packagedBroker.isNotBlank()) {
            commands += com.openminis.app.runtime.ubuntu.RuntimeProvision.installBrokerCommand(
                packagedBroker,
            )
        } else {
            commands += "if [ ! -x \"\$BIN\" ]; then echo \"minisd missing or not executable: \$BIN\" >&2; exit 40; fi"
        }
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

        commands += "if [ -d /data/adb/minis/rootfs/etc ]; then if [ ! -s /data/adb/minis/rootfs/etc/resolv.conf ]; then printf 'nameserver 223.5.5.5\\nnameserver 1.1.1.1\\nnameserver 8.8.8.8\\n' > /data/adb/minis/rootfs/etc/resolv.conf 2>/dev/null || true; fi; chmod 644 /data/adb/minis/rootfs/etc/resolv.conf 2>/dev/null || true; fi"
        commands += "if [ -d /data/adb/minis/rootfs/opt/minis/bin ]; then chmod 755 /data/adb/minis/rootfs/opt /data/adb/minis/rootfs/opt/minis /data/adb/minis/rootfs/opt/minis/bin 2>/dev/null || true; chmod 755 /data/adb/minis/rootfs/opt/minis/bin/minis-config /data/adb/minis/rootfs/opt/minis/bin/minis-model-use 2>/dev/null || true; fi"
        if (appUid > 0) {
            commands += "if [ -d /data/adb/minis/home ]; then chown $appUid:$appUid /data/adb/minis/home 2>/dev/null || true; chmod 755 /data/adb/minis/home 2>/dev/null || true; for d in .cache .local .config; do [ -d \"/data/adb/minis/home/\$d\" ] && chown -R $appUid:$appUid \"/data/adb/minis/home/\$d\" 2>/dev/null || true; done; fi"
        }
        commands += "(\"\$BIN\" --watchdog --policy \"\$POLICY\" --app-socket \"\$APP_SOCKET\" >/dev/null 2>&1 &)"
        commands += "echo \"minisd watchdog spawn requested\""
        return commands.joinToString("\n")
    }

    internal fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    /** Extracts an exact numeric uid line while tolerating su diagnostics. */
    internal fun parseEffectiveUid(output: String): Int? = output
        .lineSequence()
        .map { it.trim() }
        .firstNotNullOfOrNull { line -> line.toIntOrNull() }
}
