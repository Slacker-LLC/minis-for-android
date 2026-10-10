package com.openminis.app.permissions

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.offload.MinisNotificationListenerService
import com.openminis.app.offload.ShizukuManager
import com.openminis.app.tools.android.CommandRisk
import com.openminis.app.tools.android.PrivilegedCommandRunner
import com.openminis.app.tools.android.vscreen.VirtualScreenClientProvider
import com.openminis.app.ui.settings.assistantRoleAvailable
import com.openminis.app.ui.settings.assistantRoleHeld
import com.openminis.app.ui.settings.assistantRoleManagerOrNull
import com.openminis.app.xposed.ModuleSettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * One-tap "grant every permission the app declares", for a rooted phone: instead of the system asking
 * once per permission as the agent first needs it, the user grants them all from Settings. Besides the
 * permissions it opens this app's own accesses: the accessibility service, notification access, the default
 * assistant role, the module switches, Shizuku's permission and the virtual screen.
 *
 * It only issues fixed `pm grant` / `cmd appops set` argv for THIS package's own declared permissions;
 * nothing the agent or a model supplies reaches it, and it goes through the structured privileged runner
 * (trusted tool path, bounded argv, timeout), not a shell string.
 */
object RuntimePermissionGranter {
    /** A permission the manifest declares, and whether the system treats it as a runtime ("dangerous") one. */
    data class Declared(val name: String, val dangerous: Boolean)

    private val SPECIAL_APPOPS: Map<String, String> = mapOf(
        "android.permission.MANAGE_EXTERNAL_STORAGE" to "MANAGE_EXTERNAL_STORAGE",
        "android.permission.SYSTEM_ALERT_WINDOW" to "SYSTEM_ALERT_WINDOW",
    ) + SpecialAccess.entries.associate { it.permission to it.appOp }
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

    /**
     * Two exemptions that are neither permissions nor app-ops: the battery-optimisation allow-list and Data Saver's
     * "unrestricted data" list, both of which the system keeps by package / uid.
     */
    internal fun exemptionPlan(packageName: String, uid: Int): List<List<String>> {
        if (!NAME.matches(packageName) || uid <= 0) return emptyList()
        return listOf(
            listOf("cmd", "deviceidle", "whitelist", "+$packageName"),
            listOf("cmd", "netpolicy", "add", "restrict-background-whitelist", uid.toString()),
        )
    }

    /**
     * "Keep running in the background" and "start by itself": the standard background app-ops everywhere, and on Xiaomi / HyperOS
     * the two vendor app-ops behind its Autostart (10008) and "start in the background" (10021) switches. Other vendors keep these
     * in their own manager apps with no command; the Background page still links to them.
     */
    internal fun backgroundPlan(packageName: String, manufacturer: String): List<List<String>> {
        if (!NAME.matches(packageName)) return emptyList()
        val out = mutableListOf<List<String>>(
            listOf("cmd", "appops", "set", packageName, "RUN_IN_BACKGROUND", "allow"),
            listOf("cmd", "appops", "set", packageName, "RUN_ANY_IN_BACKGROUND", "allow"),
        )
        if (manufacturer.equals("xiaomi", ignoreCase = true) || manufacturer.equals("redmi", ignoreCase = true) ||
            manufacturer.equals("poco", ignoreCase = true)) {
            out += listOf("cmd", "appops", "set", packageName, "10008", "allow")
            out += listOf("cmd", "appops", "set", packageName, "10021", "allow")
        }
        return out
    }

    private val COMPONENT = Regex("[A-Za-z0-9_.]{1,200}/[A-Za-z0-9_.$]{1,200}")
    private const val ASSISTANT_ROLE = "android.app.role.ASSISTANT"

    /**
     * [current] (the system's `enabled_accessibility_services`) with [component] added, or null when the current
     * value holds an entry that is not a plain component name: the list is rewritten as a whole, so one entry
     * this code cannot vouch for must not be lost or altered. Already present: [current] as is.
     */
    internal fun withAccessibilityService(current: String?, component: String): String? {
        if (!COMPONENT.matches(component)) return null
        val entries = current.orEmpty().split(':').filter { it.isNotEmpty() && it != "null" }
        if (entries.any { !COMPONENT.matches(it) }) return null
        return if (entries.any { it.equals(component, ignoreCase = true) }) entries.joinToString(":")
        else (entries + component).joinToString(":")
    }

    /**
     * The commands that open this app's own accesses: its accessibility service, its notification-listener
     * services and, when the phone has the role, the default assistant. Only this package's own component
     * names go in; anything else yields no command.
     */
    internal fun accessPlan(
        packageName: String,
        accessibilityComponent: String,
        listenerComponents: List<String>,
        currentAccessibility: String?,
        assistantRoleAvailable: Boolean,
    ): List<List<String>> {
        if (!NAME.matches(packageName)) return emptyList()
        val own = "$packageName/"
        val out = mutableListOf<List<String>>()
        if (accessibilityComponent.startsWith(own)) {
            withAccessibilityService(currentAccessibility, accessibilityComponent)?.let { merged ->
                out += listOf("settings", "put", "secure", "enabled_accessibility_services", merged)
                out += listOf("settings", "put", "secure", "accessibility_enabled", "1")
            }
        }
        for (component in listenerComponents.distinct()) {
            if (component.startsWith(own) && COMPONENT.matches(component)) {
                out += listOf("cmd", "notification", "allow_listener", component)
            }
        }
        if (assistantRoleAvailable) {
            out += listOf("cmd", "role", "add-role-holder", "--user", "0", ASSISTANT_ROLE, packageName)
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

    /** The accesses that are not permissions: accessibility, notification access, the assistant role, modules. */
    private fun ownAccessesOn(context: Context): Boolean {
        if (!MinisAccessibilityService.isEnabled(context)) return false
        if (!MinisNotificationListenerService.isEnabled(context)) return false
        val role = context.assistantRoleManagerOrNull()
        if (role != null && role.assistantRoleAvailable() && !role.assistantRoleHeld()) return false
        if (!ModuleSettingsStore.SWITCHES.all { ModuleSettingsStore.isEnabled(context, it) }) return false
        // Shizuku and the virtual screen only count when Shizuku is there to be used.
        if (ShizukuManager.isInstalled() && !ShizukuManager.isReady()) return false
        if (ShizukuManager.isReady() && !VirtualScreenClientProvider.get(context).isEnabled()) return false
        return true
    }

    /** Whether every runtime permission the manifest declares, and the special accesses, are already granted. */
    fun allGranted(context: Context): Boolean = runCatching {
        ownAccessesOn(context) &&
        com.openminis.app.power.PowerOptimizationManager.isIgnoringBatteryOptimizations(context) &&
            SpecialAccess.dataSaverExempt(context) &&
            declaredPermissions(context).all { d ->
            when {
                d.name == "android.permission.MANAGE_EXTERNAL_STORAGE" ->
                    android.os.Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()
                d.name == "android.permission.SYSTEM_ALERT_WINDOW" -> android.provider.Settings.canDrawOverlays(context)
                SpecialAccess.forPermission(d.name) != null -> SpecialAccess.forPermission(d.name)!!.let { !it.applies || it.isGranted(context) }
                d.dangerous ->
                    context.checkSelfPermission(d.name) == PackageManager.PERMISSION_GRANTED
                else -> true
            }
        }
    }.getOrDefault(false)

    /** Whether the vendor autostart switch was turned on by [grantAll]; the system gives apps no way to read it back. */
    fun autostartGrantedByApp(context: Context): Boolean =
        context.getSharedPreferences("minis_oem_autostart", Context.MODE_PRIVATE).getBoolean("granted", false)

    suspend fun grantAll(context: Context): Result {
        var autostartGranted = false
        val role = context.assistantRoleManagerOrNull()
        val listeners = listOf(
            android.content.ComponentName(context, MinisNotificationListenerService::class.java).flattenToString(),
            "${context.packageName}/com.openminis.app.notifications.MinisNotificationListenerService",
        )
        val commands = plan(context.packageName, declaredPermissions(context)) +
            exemptionPlan(context.packageName, android.os.Process.myUid()) +
            backgroundPlan(context.packageName, android.os.Build.MANUFACTURER.orEmpty()) +
            accessPlan(
                packageName = context.packageName,
                accessibilityComponent = android.content.ComponentName(context, MinisAccessibilityService::class.java).flattenToString(),
                listenerComponents = listeners,
                currentAccessibility = android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ),
                assistantRoleAvailable = role != null && runCatching { role.assistantRoleAvailable() }.getOrDefault(false),
            )
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
            if (result.success) {
                succeeded++
                if (argv.getOrNull(4) == "10008") autostartGranted = true
            } else failed += label(argv)
        }
        var attempted = commands.size
        if (autostartGranted) context.getSharedPreferences("minis_oem_autostart", Context.MODE_PRIVATE).edit().putBoolean("granted", true).apply()

        // The module's switches live in the app, not in the system.
        for (key in ModuleSettingsStore.SWITCHES) {
            attempted++
            if (runCatching { ModuleSettingsStore.setEnabled(context, key, true) }.getOrDefault(false)) succeeded++ else failed += "module:$key"
        }

        // The virtual screen needs Shizuku up and a passing device probe. Shizuku's own service is not started
        // from here: that would run a script from shared storage as root.
        ShizukuManager.refresh()
        if (ShizukuManager.isReady()) {
            attempted++
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val client = VirtualScreenClientProvider.get(context)
                    client.runProbe()
                    client.setEnabled(true)
                    client.isEnabled()
                }.getOrDefault(false)
            }
            if (ok) succeeded++ else failed += "virtual-screen"
        } else if (ShizukuManager.isInstalled()) {
            failed += "shizuku-not-running"
            attempted++
        }
        return Result(attempted, succeeded, failed)
    }

    /** What to call a failed command in the message: the permission or setting it was about. */
    private fun label(argv: List<String>): String = when (argv[0]) {
        "pm" -> argv.last()
        "settings" -> argv.getOrElse(3) { "settings" }
        "cmd" -> if (argv.getOrNull(1) == "appops") argv.getOrElse(4) { "appops" } else argv.getOrElse(2) { "cmd" }
        else -> argv.last()
    }
}
