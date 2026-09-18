package com.openminis.app.xposed.system

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.openminis.app.xposed.HookInstallEntry
import com.openminis.app.xposed.HookInstallReport
import com.openminis.app.xposed.HookInstallStatus
import com.openminis.app.xposed.HookLogger
import com.openminis.app.xposed.HookRegistrar
import com.openminis.app.xposed.HookSupport
import com.openminis.app.xposed.ModulePrefs
import io.github.libxposed.api.XposedModule
import java.util.concurrent.atomic.AtomicInteger

/**
 * [T-eta-xposed-groups] Putting the assistant's software hotword detection back after the screen
 * goes off.
 *
 * Ported from Eta `hook/system/HotwordSelfHealHooks.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. Some builds tear the detection down when the display turns off and do not
 * bring it back, which is exactly when a wake word is supposed to work. The repair is deliberately
 * timid: only the default display's screen-off, only while the device really is not interactive, at
 * most three attempts spaced by [HotwordSelfHealPolicy], one heal per cooldown window, and the whole
 * thing is off unless the user asked for it.
 *
 * A screen-on cancels a pending heal - the user is back, and the platform is already in charge
 * again. Everything runs on the handler the phone window manager already owns.
 */
object HotwordSelfHealHooks {

    private const val GROUP = "HotwordSelfHeal"
    private const val PHONE_WINDOW_MANAGER_CLASS = "com.android.server.policy.PhoneWindowManager"

    @Volatile
    private var lastScreenOffScheduleUptime = 0L

    private val resumeGeneration = AtomicInteger(0)
    private val pendingLock = Any()
    private var pendingResumeHandler: Handler? = null
    private var pendingResumeRunnable: Runnable? = null

    fun install(module: Any, classLoader: ClassLoader, log: (String) -> Unit): HookInstallReport {
        val xposed = module as? XposedModule
        if (xposed == null) {
            return HookInstallReport(
                GROUP,
                listOf(
                    HookInstallEntry(
                        group = GROUP,
                        id = "system.hotword-screen-off",
                        description = "PhoneWindowManager.screenTurnedOff",
                        status = HookInstallStatus.FAILED,
                        detail = "the module entry did not pass a libxposed module instance",
                    ),
                ),
            )
        }
        val hooks = HookRegistrar(xposed, HookLogger { _, message -> log(message) }, GROUP)
        return hooks.install {
            AssistantHotword.installStubCapture(hooks, classLoader)

            val phoneWindowManager = HookSupport.findClassOrNull(classLoader, PHONE_WINDOW_MANAGER_CLASS)
            if (phoneWindowManager == null) {
                skipped(
                    "system.hotword-screen-off",
                    "PhoneWindowManager.screenTurnedOff",
                    "HotwordSelfHeal: no PhoneWindowManager on this build",
                )
                skipped(
                    "system.hotword-screen-on",
                    "PhoneWindowManager.screenTurnedOn",
                    "HotwordSelfHeal: no PhoneWindowManager on this build",
                )
                return@install
            }

            val screenTurnedOff = HookSupport.findMethod(
                phoneWindowManager,
                "screenTurnedOff",
                Int::class.javaPrimitiveType!!,
                Boolean::class.javaPrimitiveType!!,
            )
            if (screenTurnedOff != null) {
                intercept(
                    "system.hotword-screen-off",
                    screenTurnedOff,
                    "PhoneWindowManager.screenTurnedOff",
                ) { chain ->
                    val displayId = chain.getArg(0) as? Int ?: -1
                    val result = chain.proceed()
                    if (HotwordSelfHealPolicy.isPrimaryDisplay(displayId) &&
                        ModulePrefs.isEnabled(ModulePrefs.Keys.HOTWORD_SELF_HEAL)
                    ) {
                        scheduleHotwordResume(chain.getThisObject(), hooks.logger)
                    }
                    result
                }
            } else {
                missing(
                    "system.hotword-screen-off",
                    "PhoneWindowManager.screenTurnedOff",
                    "HotwordSelfHeal: screenTurnedOff(int, boolean) not found",
                )
            }

            val screenTurnedOn = HookSupport.findMethod(
                phoneWindowManager,
                "screenTurnedOn",
                Int::class.javaPrimitiveType!!,
            )
            if (screenTurnedOn != null) {
                intercept(
                    "system.hotword-screen-on",
                    screenTurnedOn,
                    "PhoneWindowManager.screenTurnedOn",
                ) { chain ->
                    val displayId = chain.getArg(0) as? Int ?: -1
                    val result = chain.proceed()
                    if (HotwordSelfHealPolicy.isPrimaryDisplay(displayId)) {
                        cancelPendingResume()
                    }
                    result
                }
            } else {
                missing(
                    "system.hotword-screen-on",
                    "PhoneWindowManager.screenTurnedOn",
                    "HotwordSelfHeal: screenTurnedOn(int) not found",
                )
            }
        }.report
    }

    private fun scheduleHotwordResume(phoneWindowManager: Any, logger: HookLogger) {
        val now = SystemClock.uptimeMillis()
        if (HotwordSelfHealPolicy.withinCooldown(now, lastScreenOffScheduleUptime)) return

        val context = HookSupport.getFieldValue(phoneWindowManager, "mContext") as? Context ?: return
        if (!isDeviceNonInteractive(context)) return
        lastScreenOffScheduleUptime = now

        val handler = HookSupport.getFieldValue(phoneWindowManager, "mHandler") as? Handler
            ?: Handler(Looper.getMainLooper())
        val generation = resumeGeneration.incrementAndGet()
        var attempt = 1

        lateinit var retryRunnable: Runnable
        retryRunnable = Runnable {
            if (resumeGeneration.get() != generation) return@Runnable
            // The switch is re-read here: it can be turned off while the attempt sits in the queue.
            if (!ModulePrefs.isEnabled(ModulePrefs.Keys.HOTWORD_SELF_HEAL)) {
                cancelPendingResume(resetCooldown = true)
                return@Runnable
            }
            if (!isDeviceNonInteractive(context)) {
                cancelPendingResume(resetCooldown = true)
                return@Runnable
            }

            val resumed = AssistantHotword.resumeSoftwareHotwordDetection(
                logger = logger,
                source = "ScreenOffHotwordSelfHeal$attempt",
                logFailures = attempt == HotwordSelfHealPolicy.RETRY_COUNT,
            )
            if (resumed) {
                cancelPendingResume(resetCooldown = false)
                logger.debug {
                    "HotwordSelfHeal: the software hotword detection is listening again"
                }
                return@Runnable
            }
            if (!HotwordSelfHealPolicy.hasAttemptsLeft(attempt)) {
                clearPendingResume(resetCooldown = false)
                return@Runnable
            }
            attempt += 1
            handler.postDelayed(retryRunnable, HotwordSelfHealPolicy.STEP_DELAY_MS)
        }

        replacePendingResume(handler, retryRunnable)
        handler.postDelayed(retryRunnable, HotwordSelfHealPolicy.INITIAL_DELAY_MS)
    }

    private fun cancelPendingResume() = cancelPendingResume(resetCooldown = true)

    private fun cancelPendingResume(resetCooldown: Boolean) {
        resumeGeneration.incrementAndGet()
        clearPendingResume(resetCooldown)
    }

    private fun replacePendingResume(handler: Handler, runnable: Runnable) {
        synchronized(pendingLock) {
            pendingResumeRunnable?.let { pendingResumeHandler?.removeCallbacks(it) }
            pendingResumeHandler = handler
            pendingResumeRunnable = runnable
        }
    }

    private fun clearPendingResume(resetCooldown: Boolean) {
        synchronized(pendingLock) {
            pendingResumeRunnable?.let { pendingResumeHandler?.removeCallbacks(it) }
            pendingResumeHandler = null
            pendingResumeRunnable = null
        }
        if (resetCooldown) {
            lastScreenOffScheduleUptime = 0L
        }
    }

    private fun isDeviceNonInteractive(context: Context): Boolean = runCatching {
        context.getSystemService(PowerManager::class.java)?.isInteractive == false
    }.getOrDefault(false)
}
