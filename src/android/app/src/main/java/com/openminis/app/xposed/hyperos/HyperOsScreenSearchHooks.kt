package com.openminis.app.xposed.hyperos

import android.app.Service
import android.content.Intent
import com.openminis.app.xposed.HookInstallJournal
import com.openminis.app.xposed.HookInstallReport
import com.openminis.app.xposed.HookInstallStatus
import com.openminis.app.xposed.HookLogger
import com.openminis.app.xposed.HookRegistrar
import com.openminis.app.xposed.HookSupport
import com.openminis.app.xposed.ModulePrefs
import com.openminis.app.xposed.system.CircleToSearchInvoker
import io.github.libxposed.api.XposedModule

/**
 * [T-eta-xposed-groups] The first real group: HyperOS hands the gesture-bar long press to its own
 * voice assistant, and this routes that request to the system's contextual search instead.
 *
 * Ported from Eta `hook/hyperos/HyperOsScreenSearchHooks.kt` (Mangi-11/Eta @ c15de97); attribution
 * in THIRD_PARTY_LICENSES.md. Eta hooks Xiaomi's `VoiceService.onStartCommand`, and so does this
 * port, with the same care: the signature is checked (not just the name), the request must be a
 * navigation long-press screen recognition (see ScreenSearchRequest), the switch must be on, and
 * the contextual-search entry must actually be available. Anything else - a different ROM, an
 * assistant request, a disabled switch, a Google app without that entry - falls through to the
 * original code instead of swallowing the gesture.
 *
 * When the takeover does happen, the service start is finished properly: `stopSelfResult` only
 * stops the service while this is still the newest start, so a request that arrived in between is
 * not dropped by this group's cleanup.
 */
object HyperOsScreenSearchHooks {

    private const val GROUP = "HyperOsScreenSearch"
    private const val SERVICE_CLASS = "com.xiaomi.voiceassistant.VoiceService"
    private const val HOOK_ID = "hyperos.screen-search-service"

    fun install(module: Any, classLoader: ClassLoader, log: (String) -> Unit): HookInstallReport {
        val xposed = module as? XposedModule
        if (xposed == null) {
            return HookInstallReport(
                GROUP,
                listOf(
                    com.openminis.app.xposed.HookInstallEntry(
                        group = GROUP,
                        id = HOOK_ID,
                        description = "VoiceService.onStartCommand",
                        status = HookInstallStatus.FAILED,
                        detail = "the module entry did not pass a libxposed module instance",
                    ),
                ),
            )
        }
        val hooks = HookRegistrar(xposed, HookLogger { _, message -> log(message) }, GROUP)
        return hooks.install {
            val serviceClass = HookSupport.findClassOrNull(classLoader, SERVICE_CLASS)
            val onStartCommand = serviceClass?.let {
                HookSupport.findMethod(it, "onStartCommand", Intent::class.java, Integer.TYPE, Integer.TYPE)
            }
            if (serviceClass == null ||
                !Service::class.java.isAssignableFrom(serviceClass) ||
                onStartCommand == null ||
                onStartCommand.declaringClass != serviceClass ||
                onStartCommand.returnType != Integer.TYPE
            ) {
                missing(
                    HOOK_ID,
                    "VoiceService.onStartCommand",
                    "HyperOS: no screen-recognition service entry with this signature",
                )
                return@install
            }

            intercept(HOOK_ID, onStartCommand, "VoiceService.onStartCommand") { chain ->
                if (!ModulePrefs.isEnabled(ModulePrefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH)) {
                    return@intercept chain.proceed()
                }
                val request = ScreenSearchRequest.from(chain.getArg(0) as? Intent)
                if (request?.matchesScreenSearch() != true) return@intercept chain.proceed()
                val service = chain.getThisObject() as? Service ?: return@intercept chain.proceed()
                if (!CircleToSearchInvoker.isAvailable(
                        service,
                        hooks.logger,
                        "HyperOS",
                        "keeping the system behaviour",
                    )
                ) {
                    return@intercept chain.proceed()
                }
                if (!CircleToSearchInvoker.trigger(hooks.logger, "HyperOS")) {
                    hooks.logger.warnThrottled("hyperos_screen_search_dispatch_failed") {
                        "HyperOS: the search request was not sent, keeping the system behaviour"
                    }
                    return@intercept chain.proceed()
                }
                finishHandledStart(service, (chain.getArg(2) as? Int) ?: 0, hooks.logger)
                Service.START_NOT_STICKY
            }
        }.report
    }

    /**
     * Cleans up the service start only while this is still the newest one: an unconditional
     * `stopSelf` would drop a request that arrived between the decision and the cleanup.
     */
    private fun finishHandledStart(service: Service, startId: Int, logger: HookLogger) {
        try {
            if (service.stopSelfResult(startId)) {
                service.stopForeground(Service.STOP_FOREGROUND_REMOVE)
            }
        } catch (exception: Exception) {
            // The search was already sent; a cleanup failure must not run the original entry
            // afterwards, which would raise the OEM screen search on top of the one we sent.
            logger.warnThrottled("hyperos_screen_search_cleanup_failed") {
                "HyperOS: cleaning up the screen-search service start failed " +
                    "(${exception.javaClass.simpleName})"
            }
        }
    }
}
