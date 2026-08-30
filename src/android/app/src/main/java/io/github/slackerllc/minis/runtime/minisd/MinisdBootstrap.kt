package io.github.slackerllc.minis.runtime.minisd

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Pure helpers for the APK-owned privileged broker bootstrap. */
internal object MinisdBootstrap {
    const val POLICY_ASSET = "minisd-policy.json"
    const val MINISD_NATIVE_NAME = "libminisd.so"
    internal const val PERSISTENT_MIGRATION_MARKER = "/data/adb/minis/.android-persistent-v1"

    /** Package Manager owns this file; ordinary APK uninstall removes it. */
    fun nativeBinaryPath(context: Context): File =
        File(context.applicationInfo.nativeLibraryDir, MINISD_NATIVE_NAME)

    fun appSocketName(appUid: Int): String {
        require(appUid > 0) { "appUid must be > 0" }
        return "@minis.minisd.app.$appUid.v1"
    }

    fun brokerSocketName(appUid: Int): String {
        require(appUid > 0) { "appUid must be > 0" }
        return "@minis.minisd.root.$appUid.v1"
    }

    fun policyForUid(template: String, appUid: Int): String {
        require(appUid > 0) { "appUid must be > 0" }
        val json = JSONObject(template)
        require(json.optJSONObject("methods") != null) { "minisd policy is missing methods" }
        val caller = json.optJSONObject("caller") ?: JSONObject().also { json.put("caller", it) }
        caller.put("appUid", appUid)
        return json.toString()
    }

    /** Reads Linux procfs starttime so a recycled PID cannot keep the broker alive. */
    fun processStartTime(pid: Int): Long? = if (pid <= 0) {
        null
    } else {
        runCatching { parseProcessStatStartTime(File("/proc/$pid/stat").readText()) }.getOrNull()
    }

    internal fun parseProcessStatStartTime(stat: String): Long? =
        stat.substringAfterLast(')', missingDelimiterValue = "")
            .trim()
            .split(Regex("\\s+"))
            .getOrNull(19)
            ?.toLongOrNull()

    /**
     * Starts an APK-owned broker watchdog. Production broker state is entirely
     * process-local: abstract sockets, inline policy, and a UID/PID/starttime
     * lease. No executable, policy, pidfile, or filesystem broker socket is
     * written below `/data/adb/minis`.
     *
     * Root prepares the #50 persistent data roots before the broker starts.
     * Legacy `filesDir` is a one-time migration input only.
     */
    fun watchdogCommand(
        appSocket: String,
        policyJson: String,
        forceRestart: Boolean,
        binaryPath: String,
        socketPath: String,
        leasePid: Int,
        leaseStartTime: Long,
    ): String {
        require(leasePid > 0) { "leasePid must be > 0" }
        require(leaseStartTime > 0) { "leaseStartTime must be > 0" }
        require(appSocket.startsWith('@')) { "app socket must be abstract" }
        require(socketPath.startsWith('@')) { "broker socket must be abstract" }
        val caller = JSONObject(policyJson).optJSONObject("caller")
        val appUid = caller?.optInt("appUid", 0) ?: 0
        require(appUid > 0) { "policy caller.appUid must be > 0" }

        val commands = mutableListOf<String>()
        commands += "BIN=${shellQuote(binaryPath)}"
        commands += "SOCKET=${shellQuote(socketPath)}"
        commands += "APP_SOCKET=${shellQuote(appSocket)}"
        commands += "APP_UID=$appUid"
        commands += "LEASE_PID=$leasePid"
        commands += "LEASE_STARTTIME=$leaseStartTime"
        commands += "POLICY_JSON=${shellQuote(policyJson)}"
        commands += "if [ ! -x \"\$BIN\" ]; then echo \"APK minisd missing or not executable: \$BIN\" >&2; exit 40; fi"
        commands += "APP_FILES=''"
        commands += "for base in /data/user/0 /data/data; do [ -d \"\$base\" ] || continue; for pkg in \"\$base\"/*; do [ -d \"\$pkg/files\" ] || continue; owner=\$(stat -c %u \"\$pkg\" 2>/dev/null || true); if [ \"\$owner\" = \"\$APP_UID\" ]; then APP_FILES=\"\$pkg/files\"; break 2; fi; done; done"
        commands += "[ -n \"\$APP_FILES\" ] || { echo 'cannot resolve current app filesDir for persistent migration' >&2; exit 41; }"
        commands += persistentDataPreparationCommand("\$APP_FILES", appUid, quoteInput = false)

        if (forceRestart) {
            commands += "for proc in /proc/[0-9]*; do pid=\${proc##*/}; [ \"\$pid\" = \"\$LEASE_PID\" ] && continue; cmdline=\$(tr '\\000' ' ' < \"\$proc/cmdline\" 2>/dev/null || true); case \"\$cmdline\" in *\"\$BIN\"*--watchdog*\"\$SOCKET\"*) kill \"\$pid\" 2>/dev/null || true ;; *\"\$BIN\"*--socket*\"\$SOCKET\"*) kill \"\$pid\" 2>/dev/null || true ;; esac; done"
            commands += "sleep 1"
        }

        commands += "(\"\$BIN\" --watchdog --socket \"\$SOCKET\" --policy-json \"\$POLICY_JSON\" --app-socket \"\$APP_SOCKET\" --lease-pid \"\$LEASE_PID\" --lease-starttime \"\$LEASE_STARTTIME\" >/dev/null 2>&1 &)"
        commands += "echo \"minisd watchdog spawn requested\""
        return commands.joinToString("\n")
    }

    /**
     * Root-side, idempotent #50 migration. The marker is the final operation:
     * interrupted copy/ownership/SELinux work therefore retries instead of
     * exposing a partially migrated persistent source as complete.
     */
    internal fun persistentDataPreparationCommand(
        appFilesDir: String,
        appUid: Int,
        quoteInput: Boolean = true,
    ): String {
        require(appUid > 0) { "appUid must be > 0" }
        if (quoteInput) require(appFilesDir.startsWith('/')) { "appFilesDir must be absolute" }
        val filesExpr = if (quoteInput) shellQuote(appFilesDir) else "\"$appFilesDir\""
        val marker = shellQuote(PERSISTENT_MIGRATION_MARKER)
        return listOf(
            "ROOT='/data/adb/minis'",
            "APP_FILES=$filesExpr",
            "MIGRATION_MARKER=$marker",
            "mkdir -p \"\$ROOT/workspace\" \"\$ROOT/sessions\" \"\$ROOT/memory\" \"\$ROOT/skills\" \"\$ROOT/shared\" \"\$ROOT/home\" || { echo 'cannot create persistent Minis data roots' >&2; exit 50; }",
            "for d in \"\$ROOT/workspace\" \"\$ROOT/sessions\" \"\$ROOT/memory\" \"\$ROOT/skills\" \"\$ROOT/shared\" \"\$ROOT/home\"; do [ ! -L \"\$d\" ] || { echo \"persistent path is symlink: \$d\" >&2; exit 51; }; chown '$appUid:$appUid' \"\$d\" || exit 52; chmod 0700 \"\$d\" || exit 53; done",
            "copy_tree() { src=\"\$1\"; dst=\"\$2\"; if [ -d \"\$src\" ] && [ ! -L \"\$src\" ]; then cp -a \"\$src/.\" \"\$dst/\" || { echo \"legacy migration failed: \$src\" >&2; exit 54; }; fi; }",
            "if [ ! -e \"\$MIGRATION_MARKER\" ]; then copy_tree \"\$APP_FILES/minis/workspace\" \"\$ROOT/workspace\"; copy_tree \"\$APP_FILES/minis-sessions\" \"\$ROOT/sessions\"; copy_tree \"\$APP_FILES/minis-global/memory\" \"\$ROOT/memory\"; copy_tree \"\$APP_FILES/minis-global/skills\" \"\$ROOT/skills\"; copy_tree \"\$APP_FILES/minis-global/shared\" \"\$ROOT/shared\"; copy_tree \"\$APP_FILES/minis-global/home\" \"\$ROOT/home\"; copy_tree \"\$ROOT/rootfs/home/minis\" \"\$ROOT/home\"; fi",
            "chown -R '$appUid:$appUid' \"\$ROOT/workspace\" \"\$ROOT/sessions\" \"\$ROOT/memory\" \"\$ROOT/skills\" \"\$ROOT/shared\" \"\$ROOT/home\" || exit 55",
            "find \"\$ROOT/workspace\" \"\$ROOT/sessions\" \"\$ROOT/memory\" \"\$ROOT/skills\" \"\$ROOT/shared\" \"\$ROOT/home\" -type d -exec chmod 0700 {} + || exit 56",
            "find \"\$ROOT/workspace\" \"\$ROOT/sessions\" \"\$ROOT/memory\" \"\$ROOT/skills\" \"\$ROOT/shared\" \"\$ROOT/home\" -type f -exec chmod u+rw,go-rwx {} + || exit 57",
            "command -v chcon >/dev/null 2>&1 || { echo 'SELinux relabel unavailable: chcon not found' >&2; exit 58; }",
            "APP_LABEL=\$(ls -Zd \"\$APP_FILES\" 2>/dev/null | awk '{print \$1; exit}')",
            "case \"\$APP_LABEL\" in u:object_r:app_data_file:s0:*) ;; *) echo \"unexpected filesDir SELinux label: \$APP_LABEL\" >&2; exit 59 ;; esac",
            "for d in \"\$ROOT/workspace\" \"\$ROOT/sessions\" \"\$ROOT/memory\" \"\$ROOT/skills\" \"\$ROOT/shared\" \"\$ROOT/home\"; do chcon -hR \"\$APP_LABEL\" \"\$d\" || { echo \"cannot relabel \$d\" >&2; exit 60; }; actual=\$(ls -Zd \"\$d\" 2>/dev/null | awk '{print \$1; exit}'); [ \"\$actual\" = \"\$APP_LABEL\" ] || { echo \"persistent SELinux label mismatch at \$d\" >&2; exit 61; }; done",
            "if [ ! -e \"\$MIGRATION_MARKER\" ]; then umask 077; : > \"\$MIGRATION_MARKER.tmp\" || exit 62; chown 0:0 \"\$MIGRATION_MARKER.tmp\" || exit 63; chmod 0600 \"\$MIGRATION_MARKER.tmp\" || exit 64; mv -f \"\$MIGRATION_MARKER.tmp\" \"\$MIGRATION_MARKER\" || exit 65; fi",
        ).joinToString("\n")
    }

    internal fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    internal fun parseEffectiveUid(output: String): Int? = output
        .lineSequence()
        .map { it.trim() }
        .firstNotNullOfOrNull { line -> line.toIntOrNull() }
}
