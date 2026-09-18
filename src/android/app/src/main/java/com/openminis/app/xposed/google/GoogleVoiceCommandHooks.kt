package com.openminis.app.xposed.google

import android.app.Activity
import android.app.KeyguardManager
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.openminis.app.xposed.HookInstallEntry
import com.openminis.app.xposed.HookInstallReport
import com.openminis.app.xposed.HookInstallStatus
import com.openminis.app.xposed.HookLogger
import com.openminis.app.xposed.HookRegistrar
import com.openminis.app.xposed.HookSupport
import com.openminis.app.xposed.ModulePrefs
import com.openminis.app.xposed.ModuleTargets
import io.github.libxposed.api.XposedModule

/**
 * [T-eta-xposed-groups] The Gemini floaty's voice command, restored after the lock screen and
 * screen-on paths.
 *
 * Ported from Eta `hook/google/GoogleAppHooks.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. On these builds the assistant's floaty surface can come back to the
 * foreground without the voice command that would have started it listening: the user sees the
 * overlay and still has to tap the microphone. This group watches the floaty activity resume and
 * sends one `ACTION_VOICE_COMMAND` back into Google's own app - but only when the matching switch
 * is on (the lock screen and screen-on cases are separate choices, both off by default) and only
 * while the state that queued it still holds.
 *
 * Eta's other half of that file - rewriting the `Build` fields and the eligibility properties so
 * the device claims to be a different one - is deliberately not ported; PROGRESS.md records why.
 */
object GoogleVoiceCommandHooks {

    private const val GROUP = "GoogleVoiceCommand"

    /** The floaty surface in Google's app; a Google app update can move or rename it. */
    private const val FLOATY_ACTIVITY_CLASS =
        "com.google.android.apps.search.assistant.surfaces.voice.robin.ui.floaty.activity.FloatyActivity"

    private const val VOICE_COMMAND_DELAY_MS = 350L
    private const val DEDUP_WINDOW_MS = 6_000L

    fun install(module: Any, classLoader: ClassLoader, log: (String) -> Unit): HookInstallReport {
        val xposed = module as? XposedModule
        if (xposed == null) {
            return HookInstallReport(
                GROUP,
                listOf(
                    HookInstallEntry(
                        group = GROUP,
                        id = "google.floaty-on-resume",
                        description = "FloatyActivity.onResume",
                        status = HookInstallStatus.FAILED,
                        detail = "the module entry did not pass a libxposed module instance",
                    ),
                ),
            )
        }
        val hooks = HookRegistrar(xposed, HookLogger { _, message -> log(message) }, GROUP)
        val gate = VoiceCommandGate(DEDUP_WINDOW_MS, SystemClock::uptimeMillis)
        return hooks.install {
            val floatyOnResume = HookSupport
                .findClassOrNull(classLoader, FLOATY_ACTIVITY_CLASS)
                ?.let { floaty ->
                    runCatching {
                        floaty.getDeclaredMethod("onResume").apply { isAccessible = true }
                    }.getOrNull()
                }
            if (floatyOnResume != null) {
                intercept("google.floaty-on-resume", floatyOnResume, "FloatyActivity.onResume") { chain ->
                    val result = chain.proceed()
                    (chain.getThisObject() as? Activity)?.let { activity ->
                        scheduleVoiceCommand(activity, hooks, gate)
                    }
                    result
                }
                return@install
            }
            // The floaty can come back without declaring its own onResume; the base callback still
            // fires for the same instance, so the class name keeps the hook to this one surface.
            val onResume = HookSupport.findMethod(Activity::class.java, "onResume")
            if (onResume == null) {
                missing(
                    "google.floaty-on-resume",
                    "Activity.onResume",
                    "Google: neither $FLOATY_ACTIVITY_CLASS nor Activity.onResume was found",
                )
                return@install
            }
            intercept("google.floaty-on-resume", onResume, "Activity.onResume(Gemini floaty)") { chain ->
                val result = chain.proceed()
                val activity = chain.getThisObject() as? Activity
                if (activity != null && activity.javaClass.name == FLOATY_ACTIVITY_CLASS) {
                    scheduleVoiceCommand(activity, hooks, gate)
                }
                result
            }
        }.report
    }

    private fun scheduleVoiceCommand(activity: Activity, hooks: HookRegistrar, gate: VoiceCommandGate) {
        val logger = hooks.logger
        val queuedWhileLocked = activity.isKeyguardLocked()
        val prefKey = GoogleVoiceCommandPolicy.prefKey(queuedWhileLocked)
        // The switch is read at interception time, so a toggle takes effect on the next resume.
        if (!ModulePrefs.isEnabled(prefKey)) return
        // Only the same instance's repeat resumes are deduplicated; a floaty closed and reopened
        // inside the window is a new request and is not suppressed.
        if (!gate.shouldSend(activity)) return

        val scenario = GoogleVoiceCommandPolicy.scenario(queuedWhileLocked)
        Handler(Looper.getMainLooper()).postDelayed({
            // Everything the user or the device may have changed while the command sat in the
            // queue is re-read here rather than trusted from scheduling time.
            val stillWanted = ModulePrefs.isEnabled(prefKey)
            val stillAlive = !activity.isFinishing && !activity.isDestroyed
            val stateUnchanged =
                GoogleVoiceCommandPolicy.stillEligible(queuedWhileLocked, activity.isKeyguardLocked())
            if (!stillWanted || !stillAlive || !stateUnchanged) {
                gate.clear(activity)
                return@postDelayed
            }
            val intent = Intent(Intent.ACTION_VOICE_COMMAND)
                .setPackage(ModuleTargets.GOOGLE_SEARCH_PACKAGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { activity.startActivity(intent) }
                .onSuccess {
                    logger.debug { "GSA: sent ACTION_VOICE_COMMAND for the $scenario floaty" }
                }
                .onFailure { exception ->
                    gate.clear(activity)
                    logger.warnThrottled("gsa_voice_command_failed") {
                        "GSA: the $scenario voice command failed " +
                            "(${exception.javaClass.simpleName}), leaving the floaty as it is"
                    }
                }
        }, VOICE_COMMAND_DELAY_MS)
    }

    private fun Activity.isKeyguardLocked(): Boolean = runCatching {
        getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
    }.getOrDefault(false)
}
