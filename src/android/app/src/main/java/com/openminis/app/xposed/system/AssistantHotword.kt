package com.openminis.app.xposed.system

import android.content.ComponentName
import com.openminis.app.xposed.HookLogger
import com.openminis.app.xposed.HookRegistrar
import com.openminis.app.xposed.HookSupport
import com.openminis.app.xposed.safeLogType

/**
 * [T-eta-xposed-groups] The hotword half of Eta's AssistantManager: resuming the software hotword
 * detection that a screen-off tears down.
 *
 * Ported from Eta `hook/system/AssistantManager.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. Eta reaches this through the voice interaction manager service: the
 * service's own stub is captured when it boots, and resuming means starting the detection session
 * the platform already created for the assistant - not creating one, and not touching an assistant
 * whose software session does not exist.
 *
 * The rest of Eta's AssistantManager (assistant selection, role plumbing, session display) is not
 * part of this file; this port carries only the path the hotword self-heal calls.
 */
object AssistantHotword {

    private const val VOICE_INTERACTION_MANAGER_SERVICE_CLASS =
        "com.android.server.voiceinteraction.VoiceInteractionManagerService"

    private const val SOFTWARE_HOTWORD_SESSION_CLASS =
        "com.android.server.voiceinteraction.SoftwareTrustedHotwordDetectorSession"

    @Volatile
    private var serviceStub: Any? = null

    /** Hooks the service's boot phase so the stub is captured before anything needs it. */
    fun installStubCapture(hooks: HookRegistrar, classLoader: ClassLoader) {
        val serviceClass = HookSupport.findClassOrNull(classLoader, VOICE_INTERACTION_MANAGER_SERVICE_CLASS)
        if (serviceClass == null) {
            hooks.skipped(
                "system.hotword-stub",
                "VoiceInteractionManagerService.onBootPhase",
                "HotwordSelfHeal: no voice interaction manager service on this build",
            )
            return
        }
        val onBootPhase = HookSupport.findMethod(serviceClass, "onBootPhase", Int::class.javaPrimitiveType!!)
        if (onBootPhase == null) {
            hooks.missing(
                "system.hotword-stub",
                "VoiceInteractionManagerService.onBootPhase",
                "HotwordSelfHeal: onBootPhase(int) not found",
            )
            return
        }
        hooks.intercept(
            "system.hotword-stub",
            onBootPhase,
            "VoiceInteractionManagerService.onBootPhase",
        ) { chain ->
            val result = chain.proceed()
            HookSupport.getFieldValue(chain.getThisObject(), "mServiceStub")?.let { serviceStub = it }
            result
        }
    }

    /**
     * Starts the existing software hotword session again. False means "nothing was resumed", which
     * the caller treats as a failed attempt rather than as a resumed one.
     */
    fun resumeSoftwareHotwordDetection(
        logger: HookLogger,
        source: String,
        logFailures: Boolean = false,
    ): Boolean {
        val stub = serviceStub ?: run {
            logFailure(logger, "${source}_hotword_stub_missing", logFailures) {
                "$source: the voice interaction manager stub is not captured yet"
            }
            return false
        }
        return runCatching {
            synchronized(stub) {
                val impl = HookSupport.getFieldValue(stub, "mImpl") ?: return@synchronized false
                val component = HookSupport.getFieldValue(impl, "mComponent") as? ComponentName
                if (!HotwordSelfHealPolicy.isGoogleAssistantComponent(component?.packageName)) {
                    return@synchronized false
                }
                val session = findSoftwareHotwordSession(impl) ?: return@synchronized false
                val alreadyRunning = HookSupport.getFieldValue(
                    session,
                    "mPerformingSoftwareHotwordDetection",
                ) as? Boolean ?: false
                if (alreadyRunning) return@synchronized false
                // The platform's own callback for that session; the detection is restarted, not
                // re-created, so whatever the assistant expects to be woken through stays the same.
                val callback = HookSupport.getFieldValue(session, "mSoftwareCallback")
                    ?: return@synchronized false
                val startListening = impl.javaClass.declaredMethods.firstOrNull { method ->
                    method.name == "startListeningFromMicLocked" && method.parameterTypes.size == 2
                }?.apply { isAccessible = true } ?: return@synchronized false
                startListening.invoke(impl, null, callback)
                true
            }
        }.getOrElse { failure ->
            logFailure(logger, "${source}_hotword_resume_failed", logFailures) {
                "$source: resuming the software hotword detection failed (${failure.safeLogType()})"
            }
            false
        }
    }

    private fun findSoftwareHotwordSession(impl: Any): Any? {
        val connection = HookSupport.getFieldValue(impl, "mHotwordDetectionConnection") ?: return null
        val sessions = HookSupport.getFieldValue(connection, "mDetectorSessions") ?: return null
        val size = HookSupport.findMethod(sessions.javaClass, "size") ?: return null
        val valueAt = HookSupport.findMethod(
            sessions.javaClass,
            "valueAt",
            Int::class.javaPrimitiveType!!,
        ) ?: return null
        val count = size.invoke(sessions) as? Int ?: return null
        repeat(count) { index ->
            val session = valueAt.invoke(sessions, index) ?: return@repeat
            if (session.javaClass.name == SOFTWARE_HOTWORD_SESSION_CLASS) return session
        }
        return null
    }

    private fun logFailure(
        logger: HookLogger,
        key: String,
        logFailures: Boolean,
        message: () -> String,
    ) {
        if (!logFailures) return
        logger.warnThrottled(key) { message() }
    }
}
