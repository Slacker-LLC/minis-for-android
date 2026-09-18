package com.openminis.app.xposed.colordirect

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Parcelable
import android.os.Build
import android.os.SystemClock
import com.openminis.app.xposed.HookInstallEntry
import com.openminis.app.xposed.HookInstallReport
import com.openminis.app.xposed.HookInstallStatus
import com.openminis.app.xposed.HookLogger
import com.openminis.app.xposed.HookRegistrar
import com.openminis.app.xposed.HookSupport
import com.openminis.app.xposed.ModulePrefs
import com.openminis.app.xposed.system.CircleToSearchInvoker
import io.github.libxposed.api.XposedModule

/**
 * [T-eta-xposed-groups] ColorOS's own two-finger screen recognition, handed to the system's
 * contextual search instead.
 *
 * Ported from Eta `hook/colordirect/ColorDirectHooks.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. ColorOS routes every direct-service gesture through one activity, so the
 * hook claims only the report that really is the two-finger recognition - and only when the user
 * turned that switch on, which is off by default because it replaces a gesture the device already
 * has. When the system's contextual search is not available the original path runs; a gesture is
 * never swallowed for a search that did not happen.
 */
object ColorDirectHooks {

    private const val GROUP = "ColorDirect"
    private const val SOURCE = "ColorDirectActivity"

    private const val COLLECT_ACTIVITY_CLASS = "com.coloros.directui.ui.CollectInfoActivity"
    private const val START_INFO_CLASS = "com.oplus.infocollection.data.CollectionStartInfo"
    private const val EXTRA_START_INFO = "startInfo"
    private const val EXTRA_DIRECT_EXT = "directExt"

    @Volatile
    private var lastHandledUptime = 0L

    fun install(module: Any, classLoader: ClassLoader, log: (String) -> Unit): HookInstallReport {
        val xposed = module as? XposedModule
        if (xposed == null) {
            return HookInstallReport(
                GROUP,
                listOf(
                    HookInstallEntry(
                        group = GROUP,
                        id = "colordirect.collect-info",
                        description = "CollectInfoActivity.M(Intent)",
                        status = HookInstallStatus.FAILED,
                        detail = "the module entry did not pass a libxposed module instance",
                    ),
                ),
            )
        }
        val hooks = HookRegistrar(xposed, HookLogger { _, message -> log(message) }, GROUP)
        return hooks.install {
            val activityClass = HookSupport.findClassOrNull(classLoader, COLLECT_ACTIVITY_CLASS)
            if (activityClass == null) {
                missing(
                    "colordirect.collect-info",
                    "CollectInfoActivity.M(Intent)",
                    "ColorDirect: no CollectInfoActivity on this build",
                )
                return@install
            }
            val startInfoClass = HookSupport.findClassOrNull(classLoader, START_INFO_CLASS)
            val method = HookSupport.findMethod(activityClass, "M", Intent::class.java)
            if (method == null) {
                missing(
                    "colordirect.collect-info",
                    "CollectInfoActivity.M(Intent)",
                    "ColorDirect: CollectInfoActivity.M(Intent) not found",
                )
                return@install
            }
            intercept(
                "colordirect.collect-info",
                method,
                "CollectInfoActivity.M(Intent)",
            ) { chain ->
                // The switch is read here, so turning it off restores the stock gesture at once.
                if (!ModulePrefs.isEnabled(ModulePrefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH)) {
                    return@intercept chain.proceed()
                }
                val activity = chain.getThisObject() as? Activity
                val intent = chain.getArg(0) as? Intent
                if (activity == null ||
                    !ColorDirectTriggerPolicy.isDoubleFingerCollect(resolveDirectExt(intent, startInfoClass))
                ) {
                    return@intercept chain.proceed()
                }
                if (tryStartCircleToSearch(activity, hooks.logger)) {
                    finishColorDirectActivity(activity)
                    null
                } else {
                    chain.proceed()
                }
            }
        }.report
    }

    private fun tryStartCircleToSearch(context: Context, logger: HookLogger): Boolean {
        val now = SystemClock.uptimeMillis()
        if (ColorDirectTriggerPolicy.withinDedupWindow(now, lastHandledUptime)) {
            logger.debug { "$SOURCE: inside the dedup window, swallowing the repeated report" }
            return true
        }
        if (!CircleToSearchInvoker.isAvailable(context, logger, SOURCE, "keeping the ColorOS gesture")) {
            return false
        }
        if (!CircleToSearchInvoker.trigger(logger, "$SOURCE two-finger gesture")) {
            return false
        }
        lastHandledUptime = now
        logger.debug { "$SOURCE: the two-finger gesture was handed to contextual search" }
        return true
    }

    private fun finishColorDirectActivity(activity: Activity) {
        activity.finishAndRemoveTask()
        // [T-eta-xposed-groups] Eta targets Android 37-era ROMs; the no-animation transition is
        // only available from 34, and on anything older the card simply finishes as it always did.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            activity.overrideActivityTransition(Activity.OVERRIDE_TRANSITION_CLOSE, 0, 0)
        }
    }

    private fun resolveDirectExt(intent: Intent?, startInfoClass: Class<*>?): String? {
        if (intent == null) return null
        intent.getStringExtra(EXTRA_DIRECT_EXT)?.let { return it }
        val startInfo = resolveStartInfo(intent, startInfoClass) ?: return null
        return HookSupport.invokeNoArgs(startInfo, "getDirectExt") as? String
    }

    @Suppress("DEPRECATION")
    private fun resolveStartInfo(intent: Intent, startInfoClass: Class<*>?): Any? {
        if (startInfoClass != null && Parcelable::class.java.isAssignableFrom(startInfoClass)) {
            @Suppress("UNCHECKED_CAST")
            val typed = startInfoClass as Class<Parcelable>
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(EXTRA_START_INFO, typed)?.let { return it }
            }
        }
        return runCatching { intent.extras?.get(EXTRA_START_INFO) }.getOrNull()
    }
}
