package com.openminis.app.tools.android

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.offload.ShizukuManager
import com.openminis.app.runtime.ubuntu.UbuntuRuntime
import org.json.JSONArray
import org.json.JSONObject

/** Four-state capability result used by every Android debug tool. */
enum class CapabilityStatus { AVAILABLE, PARTIAL, UNAVAILABLE, REQUIRES_USER_GRANT }

data class CapabilityFact(
    val status: CapabilityStatus,
    val detail: String,
    val backend: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("status", status.name)
        .put("detail", detail)
        .apply { backend?.let { put("backend", it) } }
}

/** Passive, side-effect-free capability inventory. It never starts `su`. */
object AndroidCapabilityResolver {
    private const val CAP_SYS_CHROOT = 18
    private const val CAP_SYS_ADMIN = 21

    fun resolve(context: Context): JSONObject {
        val root = RootCommandRunner.cachedProbe()
        val suPath = RootCommandRunner.passiveSuPath()
        val shizuku = ShizukuManager.snapshot.value
        val service = MinisAccessibilityService.getInstance()
        val serviceInfo = service?.serviceInfo
        val rootStatus = when {
            root?.authorized == true -> CapabilityStatus.AVAILABLE
            suPath != null -> CapabilityStatus.REQUIRES_USER_GRANT
            else -> CapabilityStatus.UNAVAILABLE
        }
        val shizukuStatus = when (shizuku.state) {
            ShizukuManager.State.READY -> CapabilityStatus.AVAILABLE
            ShizukuManager.State.NEED_PERMISSION -> CapabilityStatus.REQUIRES_USER_GRANT
            ShizukuManager.State.NOT_RUNNING -> CapabilityStatus.REQUIRES_USER_GRANT
            ShizukuManager.State.NOT_INSTALLED -> CapabilityStatus.UNAVAILABLE
        }
        val privilegedAvailable = root?.authorized == true || shizuku.state == ShizukuManager.State.READY

        return JSONObject().apply {
            put("root", JSONObject().apply {
                put("status", rootStatus.name)
                put("passiveSuDetected", suPath != null)
                suPath?.let { put("suPath", it) }
                put("authorized", root?.authorized == true)
                root?.effectiveUid?.let { put("effectiveUid", it) }
                root?.effectiveGid?.let { put("effectiveGid", it) }
                put("groups", JSONArray(root?.groups.orEmpty()))
                root?.effectiveCapabilitiesHex?.let { put("effectiveCapabilities", it) }
                root?.selinuxContext?.let { put("selinuxContext", it) }
                root?.selinuxMode?.let { put("selinuxMode", it) }
                root?.error?.let { put("lastProbeError", it) }
                put("provider", JSONObject.NULL) // provider names never decide capability.
            })
            put("privilegedShell", JSONObject().apply {
                put("root", CapabilityFact(rootStatus, when (rootStatus) {
                    CapabilityStatus.AVAILABLE -> "active su probe confirmed effective uid 0"
                    CapabilityStatus.REQUIRES_USER_GRANT -> "su exists; active authorization was not requested"
                    else -> "no executable su was passively detected"
                }, "root").toJson())
                put("shizuku", CapabilityFact(
                    shizukuStatus,
                    "state=${shizuku.state}, uid=${shizuku.uid}, provider=${if (shizuku.isSui) "sui" else "shizuku-protocol"}",
                    "shizuku",
                ).toJson())
            })
            put("ui", JSONObject().apply {
                put("accessibilityConnected", fact(
                    service != null,
                    "MinisAccessibilityService ${if (service == null) "is not connected" else "is connected"}",
                ))
                put("canRetrieveWindowContent", fact(
                    ((serviceInfo?.capabilities ?: 0) and AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT) != 0,
                    "Accessibility window-content capability",
                ))
                put("canPerformGestures", fact(
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                        ((serviceInfo?.capabilities ?: 0) and AccessibilityServiceInfo.CAPABILITY_CAN_PERFORM_GESTURES) != 0,
                    "Accessibility gesture capability (API ${Build.VERSION.SDK_INT})",
                ))
                val screenshotCap = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && service != null &&
                    ((serviceInfo?.capabilities ?: 0) and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT) != 0
                put("canTakeScreenshot", CapabilityFact(
                    when {
                        Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> CapabilityStatus.UNAVAILABLE
                        service == null -> CapabilityStatus.REQUIRES_USER_GRANT
                        screenshotCap -> CapabilityStatus.AVAILABLE
                        else -> CapabilityStatus.PARTIAL
                    },
                    "Accessibility screenshot API requires API 30+, a connected service, OEM support, and a non-secure window",
                    "accessibility",
                ).toJson())
                put("screenshotApiLevel", Build.VERSION.SDK_INT)
            })
            put("debug", JSONObject().apply {
                put("logcat", CapabilityFact(
                    if (privilegedAvailable) CapabilityStatus.AVAILABLE else CapabilityStatus.PARTIAL,
                    if (privilegedAvailable) "full-device logcat through a privileged shell" else "normal app can only read its own log stream",
                    if (root?.authorized == true) "root" else if (shizuku.state == ShizukuManager.State.READY) "shizuku" else "app",
                ).toJson())
                put("dumpsys", CapabilityFact(
                    if (privilegedAvailable) CapabilityStatus.AVAILABLE else CapabilityStatus.PARTIAL,
                    if (privilegedAvailable) "privileged dumpsys available" else "ordinary SDK APIs and app-owned diagnostics only",
                ).toJson())
                put("meminfo", CapabilityFact(
                    if (privilegedAvailable) CapabilityStatus.AVAILABLE else CapabilityStatus.PARTIAL,
                    if (privilegedAvailable) "dumpsys meminfo is available" else "memory details are limited to the OpenMinis process",
                ).toJson())
                put("debuggerd", rootOnlyFact(root, "debuggerd requires authorized root and target access"))
                put("dropbox", CapabilityFact(
                    if (privilegedAvailable) CapabilityStatus.PARTIAL else CapabilityStatus.UNAVAILABLE,
                    "DropBox visibility depends on Android version, shell grants, and OEM policy",
                ).toJson())
                put("tombstones", rootOnlyFact(root, "tombstone directory access is SELinux- and ROM-dependent"))
                put("anrTraces", rootOnlyFact(root, "ANR trace access is SELinux- and ROM-dependent"))
            })
            put("execution", executionFacts(context, root))
            put("packageVisibility", packageVisibility(context))
            put("security", JSONObject().apply {
                put("selinuxMode", root?.selinuxMode ?: "unknown")
                put("selinuxContext", root?.selinuxContext ?: "unknown")
                put("uidZeroDoesNotImplyAllCapabilities", true)
                put("globalSelinuxChangesAllowed", false)
            })
        }
    }

    private fun executionFacts(context: Context, root: RootProbeResult?): JSONObject {
        val runtime = UbuntuRuntime.snapshot.value
        val stat = StatFs(context.filesDir.absolutePath)
        val chrootBit = root?.authorized == true && root.hasCapability(CAP_SYS_CHROOT)
        val adminBit = root?.authorized == true && root.hasCapability(CAP_SYS_ADMIN)
        val ubuntuStatus = when {
            runtime.running && runtime.provisioned -> CapabilityStatus.AVAILABLE
            runtime.running -> CapabilityStatus.PARTIAL
            runtime.available -> CapabilityStatus.PARTIAL
            else -> CapabilityStatus.UNAVAILABLE
        }
        val ubuntuDetail = when {
            runtime.running && runtime.provisioned ->
                "Ubuntu 24.04 is running through minisd; per-session workspace is prepared on first exec"
            runtime.running -> "Ubuntu runtime is running but provisioning is not confirmed"
            runtime.lastError != null -> "Ubuntu runtime unavailable: ${runtime.lastError}"
            else -> "Ubuntu runtime is not started; capability probes require minisd"
        }
        return JSONObject().apply {
            put("defaultEnvironment", "ubuntu")
            put("ubuntu", CapabilityFact(ubuntuStatus, ubuntuDetail, "minisd").toJson())
            put("sessionWorkspace", CapabilityFact(
                if (runtime.running) CapabilityStatus.PARTIAL else CapabilityStatus.UNAVAILABLE,
                if (runtime.running) {
                    "Created and bound by minisd for each session; App does not access /data/adb/minis directly"
                } else {
                    "Requires a running Ubuntu/minisd runtime"
                },
                "minisd",
            ).toJson())
            put("java", CapabilityFact(
                if (runtime.running) CapabilityStatus.PARTIAL else CapabilityStatus.UNAVAILABLE,
                if (runtime.running) "Requires a guest probe for /usr/bin/java" else "Ubuntu runtime is unavailable",
                "ubuntu",
            ).toJson())
            put("gradle", CapabilityFact(
                if (runtime.running) CapabilityStatus.PARTIAL else CapabilityStatus.UNAVAILABLE,
                if (runtime.running) "Gradle wrapper is project-specific; verify it with shell_execute in Ubuntu" else "Ubuntu runtime is unavailable",
                "ubuntu",
            ).toJson())
            put("androidSdk", CapabilityFact(
                if (runtime.running) CapabilityStatus.PARTIAL else CapabilityStatus.UNAVAILABLE,
                if (runtime.running) "Requires a guest probe; SDK is not read from App-private paths" else "Ubuntu runtime is unavailable",
                "ubuntu",
            ).toJson())
            put("platforms", JSONArray())
            put("buildTools", JSONArray())
            put("ndk", CapabilityFact(
                if (runtime.running) CapabilityStatus.PARTIAL else CapabilityStatus.UNAVAILABLE,
                "Requires a guest probe inside Ubuntu",
                "ubuntu",
            ).toJson())
            put("cmake", CapabilityFact(
                if (runtime.running) CapabilityStatus.PARTIAL else CapabilityStatus.UNAVAILABLE,
                "Requires a guest probe inside Ubuntu",
                "ubuntu",
            ).toJson())
            put("disk", JSONObject().put("availableBytes", stat.availableBytes).put("totalBytes", stat.totalBytes))
            put("chroot", CapabilityFact(
                when {
                    root?.authorized != true -> if (RootCommandRunner.passiveSuPath() != null) CapabilityStatus.REQUIRES_USER_GRANT else CapabilityStatus.UNAVAILABLE
                    chrootBit -> CapabilityStatus.PARTIAL
                    else -> CapabilityStatus.UNAVAILABLE
                },
                if (chrootBit) "CAP_SYS_CHROOT bit is present; native chroot remains experimental and operation probes are still required" else "CAP_SYS_CHROOT not confirmed",
                "root",
            ).toJson())
            put("mount", CapabilityFact(
                if (adminBit) CapabilityStatus.PARTIAL else CapabilityStatus.UNAVAILABLE,
                if (adminBit) "CAP_SYS_ADMIN bit is present; mount/bind/namespace still require active operation probes" else "CAP_SYS_ADMIN not confirmed",
                "root",
            ).toJson())
            put("bindMount", CapabilityFact(if (adminBit) CapabilityStatus.PARTIAL else CapabilityStatus.UNAVAILABLE, "requires mount permission, namespace behavior, and SELinux acceptance", "root").toJson())
            put("mountNamespace", CapabilityFact(if (adminBit) CapabilityStatus.PARTIAL else CapabilityStatus.UNAVAILABLE, "requires an active unshare/nsenter probe", "root").toJson())
            put("nativeChrootExperimental", true)
            put("nativeChrootDefault", false)
            put("selfUpdateContinuousExecution", "UNSUPPORTED")
        }
    }

    private fun packageVisibility(context: Context): JSONObject {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val visibleLaunchers = runCatching {
            context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY).size
        }.getOrDefault(0)
        return JSONObject()
            .put("status", if (visibleLaunchers > 0) CapabilityStatus.PARTIAL.name else CapabilityStatus.UNAVAILABLE.name)
            .put("visibleLauncherCount", visibleLaunchers)
            .put("queryAllPackages", false)
            .put("detail", "Android 11+ package visibility applies; privileged pm is used only when authorized")
    }

    private fun fact(available: Boolean, detail: String): JSONObject =
        CapabilityFact(if (available) CapabilityStatus.AVAILABLE else CapabilityStatus.UNAVAILABLE, detail).toJson()

    private fun rootOnlyFact(root: RootProbeResult?, detail: String): JSONObject = CapabilityFact(
        when {
            root?.authorized == true -> CapabilityStatus.PARTIAL
            RootCommandRunner.passiveSuPath() != null -> CapabilityStatus.REQUIRES_USER_GRANT
            else -> CapabilityStatus.UNAVAILABLE
        },
        detail,
        "root",
    ).toJson()
}
