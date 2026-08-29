package com.openminis.app.sandbox.minisd

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

    fun watchdogCommand(
        appSocket: String,
        policyJson: String,
        forceRestart: Boolean,
        appMountNamespacePid: Int,
    ): String {
        require(appMountNamespacePid > 0) { "appMountNamespacePid must be > 0" }
        val appUid = JSONObject(policyJson).optJSONObject("caller")?.optInt("appUid", 0) ?: 0
        require(appUid > 0) { "policy caller.appUid must be > 0" }

        val commands = mutableListOf<String>()
        commands += "BIN=${shellQuote(MinisdProtocol.DEFAULT_BIN)}"
        commands += "ROOTFS=${shellQuote(MinisdProtocol.DEFAULT_ROOTFS)}"
        commands += "POLICY=${shellQuote(POLICY_PATH)}"
        commands += "APP_SOCKET=${shellQuote(appSocket)}"
        commands += "APP_MNT_PID=$appMountNamespacePid"
        commands += "APP_UID=$appUid"
        commands += "if [ ! -x \"\$BIN\" ]; then echo \"minisd missing or not executable: \$BIN\" >&2; exit 40; fi"
        commands += "if [ ! -f \"\$ROOTFS/etc/os-release\" ]; then echo \"ubuntu rootfs missing: \$ROOTFS/etc/os-release\" >&2; exit 41; fi"
        commands += "if [ ! -e \"\$ROOTFS/bin/bash\" ] && [ ! -e \"\$ROOTFS/usr/bin/bash\" ] && [ ! -e \"\$ROOTFS/bin/sh\" ]; then echo \"ubuntu rootfs missing shell under \$ROOTFS\" >&2; exit 42; fi"
        commands += "mkdir -p ${shellQuote(POLICY_DIR)} || { echo \"cannot create minisd policy directory\" >&2; exit 43; }"
        commands += "umask 077"
        commands += "printf '%s' ${shellQuote(policyJson)} > \"\$POLICY.tmp\" || { echo \"cannot write minisd policy\" >&2; exit 44; }"
        commands += "mv -f \"\$POLICY.tmp\" \"\$POLICY\" || { echo \"cannot install minisd policy\" >&2; exit 45; }"

        if (forceRestart) {
            commands += "pid=\"\""
            commands += "if [ -r ${shellQuote(PID_FILE)} ]; then pid=\$(cat ${shellQuote(PID_FILE)} 2>/dev/null || true); fi"
            commands += "case \"\$pid\" in ''|*[!0-9]*) ;; *) child_cmd=\$(tr '\\000' ' ' < \"/proc/\$pid/cmdline\" 2>/dev/null || true); case \"\$child_cmd\" in *minisd*--socket*/data/adb/minis/run/minisd.sock*) ppid=\$(awk '/^PPid:/{print \$2; exit}' \"/proc/\$pid/status\" 2>/dev/null || true); case \"\$ppid\" in ''|*[!0-9]*) ;; *) parent_cmd=\$(tr '\\000' ' ' < \"/proc/\$ppid/cmdline\" 2>/dev/null || true); case \"\$parent_cmd\" in *minisd*--watchdog*) kill \"\$ppid\" 2>/dev/null || true; kill \"\$pid\" 2>/dev/null || true ;; esac ;; esac ;; esac ;; esac"
            commands += "sleep 1"
        }

        // The PID comes from the live Android process. Authenticate it against
        // the same UID already embedded in the minisd policy before allowing a
        // privileged process to enter its mount namespace. The expected UID is
        // also passed into minisd so it can repeat the check against the exact
        // proc handle used for setns, closing the shell-check/setns race.
        commands += "target_uid=\$(awk '/^Uid:/{print \$2; exit}' \"/proc/\$APP_MNT_PID/status\" 2>/dev/null || true)"
        commands += "if [ \"\$target_uid\" != \"\$APP_UID\" ] || [ ! -e \"/proc/\$APP_MNT_PID/ns/mnt\" ]; then echo \"app mount namespace target mismatch: pid=\$APP_MNT_PID uid=\${target_uid:-missing} expected=\$APP_UID\" >&2; exit 46; fi"
        commands += "(MINIS_EXPECTED_APP_UID=\"\$APP_UID\" \"\$BIN\" --watchdog --mount-ns-pid \"\$APP_MNT_PID\" --policy \"\$POLICY\" --app-socket \"\$APP_SOCKET\" >/dev/null 2>&1 &)"
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
