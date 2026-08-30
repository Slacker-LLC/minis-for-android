package io.github.slackerllc.minis.runtime.minisd

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Pure helpers for locating the APK-owned broker and creating its bootstrap command. */
internal object MinisdBootstrap {
    const val POLICY_ASSET = "minisd-policy.json"
    const val MINISD_NATIVE_NAME = "libminisd.so"

    /** Package Manager owns this file; it disappears with the APK on uninstall. */
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
     * Starts a broker watchdog with all volatile state in memory. The broker
     * exits when the app lease pid disappears, so an APK uninstall/force-stop
     * cannot leave a root daemon running forever.
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
        val commands = mutableListOf<String>()
        commands += "BIN=${shellQuote(binaryPath)}"
        commands += "SOCKET=${shellQuote(socketPath)}"
        commands += "APP_SOCKET=${shellQuote(appSocket)}"
        commands += "LEASE_PID=$leasePid"
        commands += "LEASE_STARTTIME=$leaseStartTime"
        commands += "POLICY_JSON=${shellQuote(policyJson)}"
        commands += "if [ ! -x \"\$BIN\" ]; then echo \"minisd missing or not executable: \$BIN\" >&2; exit 40; fi"

        if (forceRestart) {
            // No pidfile is trusted. Only terminate processes whose complete
            // cmdline names this exact APK-owned binary and socket pair.
            commands += "for proc in /proc/[0-9]*; do pid=\${proc##*/}; [ \"\$pid\" = \"\$LEASE_PID\" ] && continue; cmdline=\$(tr '\\000' ' ' < \"\$proc/cmdline\" 2>/dev/null || true); case \"\$cmdline\" in *\"\$BIN\"*--watchdog*\"\$SOCKET\"*) kill \"\$pid\" 2>/dev/null || true ;; *\"\$BIN\"*--socket*\"\$SOCKET\"*) kill \"\$pid\" 2>/dev/null || true ;; esac; done"
            commands += "sleep 1"
        }

        commands += "(\"\$BIN\" --watchdog --socket \"\$SOCKET\" --policy-json \"\$POLICY_JSON\" --app-socket \"\$APP_SOCKET\" --lease-pid \"\$LEASE_PID\" --lease-starttime \"\$LEASE_STARTTIME\" >/dev/null 2>&1 &)"
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
