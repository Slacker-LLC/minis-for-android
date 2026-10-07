package com.openminis.app.permissions

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import com.openminis.app.tools.android.CommandRisk
import com.openminis.app.tools.android.PrivilegedCommandRunner

/**
 * One-tap "grant every permission the app declares", for a rooted phone: instead of the system asking
 * once per permission as the agent first needs it, the user grants them all from Settings.
 *
 * It only issues fixed `pm grant` / `cmd appops set` argv for THIS package's own declared permissions;
 * nothing the agent or a model supplies reaches it, and it goes through the structured privileged runner
 * (trusted tool path, bounded argv, timeout), not a shell string.
 */
object RuntimePermissionGranter {
    /** A permission the manifest declares, and whether the system treats it as a runtime ("dangerous") one. */
    data class Declared(val name: String, val dangerous: Boolean)

    private val SPECIAL_APPOPS = mapOf(
        "android.permission.MANAGE_EXTERNAL_STORAGE" to "MANAGE_EXTERNAL_STORAGE",
        "android.permission.SYSTEM_ALERT_WINDOW" to "SYSTEM_ALERT_WINDOW",
    )
    private val NAME = Regex("[A-Za-z0-9_.]{1,200}")

    /** The commands to run, as argv, in order. Names that are not plain identifiers are dropped. */
    internal fun plan(packageName: String, declared: List<Declared>): List<List<String>> {
        if (!NAME.matches(packageName)) return emptyList()
        val out = mutableListOf<List<String>>()
        for (d in declared.distinctBy { it.name }) {
            if (!NAME.matches(d.name)) continue
            val appOp = SPECIAL_APPOPS[d.name]
            when {
                appOp != null -> out += listOf("cmd", "appops", "set", packageName, appOp, "allow")
                d.dangerous -> out += listOf("pm", "grant", packageName, d.name)
            }
        }
        return out
    }

    data class Result(
        val attempted: Int,
        val succeeded: Int,
        val failed: List<String>,
        /** Why nothing could be done at all (no root), or null. */
        val unavailable: String? = null,
    )

    /** Reads what this package's manifest requests. */
    internal fun declaredPermissions(context: Context): List<Declared> {
        val pm = context.packageManager
        val info = pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        return info.requestedPermissions.orEmpty().map { name ->
            val dangerous = runCatching {
                @Suppress("DEPRECATION")
                val level = pm.getPermissionInfo(name, 0).protectionLevel
                level and PermissionInfo.PROTECTION_MASK_BASE == PermissionInfo.PROTECTION_DANGEROUS
            }.getOrDefault(false)
            Declared(name, dangerous)
        }
    }

    /** Whether every runtime permission the manifest declares, and the two special accesses, are already granted. */
    fun allGranted(context: Context): Boolean = runCatching {
        declaredPermissions(context).all { d ->
            when {
                d.name == "android.permission.MANAGE_EXTERNAL_STORAGE" ->
                    android.os.Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()
                d.name == "android.permission.SYSTEM_ALERT_WINDOW" -> android.provider.Settings.canDrawOverlays(context)
                d.dangerous ->
                    context.checkSelfPermission(d.name) == PackageManager.PERMISSION_GRANTED
                else -> true
            }
        }
    }.getOrDefault(false)

    suspend fun grantAll(context: Context): Result {
        val commands = plan(context.packageName, declaredPermissions(context))
        var succeeded = 0
        val failed = mutableListOf<String>()
        for (argv in commands) {
            val result = PrivilegedCommandRunner.run(
                context = context,
                sessionId = "settings",
                argv = argv,
                operation = "grant-own-permission",
                risk = CommandRisk.MUTATING,
                timeoutMs = 15_000L,
                rootOnly = true,
            )
            result.unavailableReason?.let { return Result(commands.size, succeeded, failed, unavailable = it) }
            if (result.success) succeeded++ else failed += argv.takeLast(if (argv[0] == "pm") 1 else 2).first()
        }
        return Result(commands.size, succeeded, failed)
    }
}
