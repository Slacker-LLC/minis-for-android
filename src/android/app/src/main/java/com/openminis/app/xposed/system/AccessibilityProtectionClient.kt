package com.openminis.app.xposed.system

import android.app.BroadcastOptions
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Build
import android.os.Looper
import android.provider.Settings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [T-eta-xposed-groups] The app's side of the accessibility protection: it asks, it never writes.
 *
 * Ported from Eta `agent/accessibility/AccessibilityProtectionClient.kt` (Mangi-11/Eta @ c15de97);
 * attribution in THIRD_PARTY_LICENSES.md. The switch is the user's, the secure setting belongs to
 * the backend that lives in system_server, and this client only carries the request there and
 * reports what came back. It keeps its own copy of the last applied value, because the setting
 * itself may be unreadable to the app on some releases - and a UI that cannot read the real state
 * must not invent one.
 */
object AccessibilityProtectionClient {

    private const val PREFERENCES_NAME = "accessibility_protection"
    private const val PREFERENCE_ENABLED = "enabled"
    private const val CONTROL_TIMEOUT_MS = 2_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    /** The last state the backend confirmed, falling back to what the setting says, then to off. */
    fun isEnabled(context: Context): Boolean {
        val appContext = context.applicationContext
        val fallback = appContext
            .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
            .getBoolean(PREFERENCE_ENABLED, AccessibilityProtectionProtocol.DEFAULT_ENABLED)
        return try {
            Settings.Global.getInt(
                appContext.contentResolver,
                AccessibilityProtectionProtocol.SETTING_NAME,
                if (fallback) 1 else 0,
            ) == 1
        } catch (_: RuntimeException) {
            fallback
        }
    }

    /** Asks the backend to turn the protection on or off, and reports the state that resulted. */
    fun setEnabled(context: Context, enabled: Boolean, onResult: (ControlResult) -> Unit) {
        sendRequest(
            context = context.applicationContext,
            action = AccessibilityProtectionProtocol.ACTION_SET,
            enabled = enabled,
            scheduler = mainHandler,
        ) { result ->
            if (result.status == ControlStatus.APPLIED) {
                context.applicationContext
                    .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(PREFERENCE_ENABLED, result.enabled)
                    .apply()
            }
            onResult(result)
        }
    }

    /**
     * Asks the backend to repair the service now, for callers that are about to need it. Blocking
     * and therefore refused on the main thread; an unanswered request is UNAVAILABLE, which the
     * caller treats as "the module could not help" rather than as a success.
     */
    fun requestRecoveryBlocking(context: Context): ControlStatus {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return ControlStatus.UNAVAILABLE
        }
        val latch = CountDownLatch(1)
        var status = ControlStatus.UNAVAILABLE
        sendRequest(
            context = context.applicationContext,
            action = AccessibilityProtectionProtocol.ACTION_RECOVER,
            enabled = true,
            scheduler = mainHandler,
        ) { result ->
            status = result.status
            latch.countDown()
        }
        val completed = runCatching {
            latch.await(CONTROL_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }.getOrDefault(false)
        return if (completed) status else ControlStatus.UNAVAILABLE
    }

    /** How the backend's result code maps to something a caller can act on. */
    fun statusOf(resultCode: Int): ControlStatus = when (resultCode) {
        AccessibilityProtectionProtocol.RESULT_APPLIED -> ControlStatus.APPLIED
        AccessibilityProtectionProtocol.RESULT_REJECTED -> ControlStatus.REJECTED
        else -> ControlStatus.UNAVAILABLE
    }

    private fun sendRequest(
        context: Context,
        action: String,
        enabled: Boolean,
        scheduler: Handler,
        onResult: (ControlResult) -> Unit,
    ) {
        // The backend receives these as an ordered broadcast addressed to the system package; it
        // answers through the result code and puts the state it ended up in into the result extras.
        val intent = Intent(action)
            .setPackage(AccessibilityProtectionProtocol.RECEIVER_PACKAGE)
            .putExtra(
                AccessibilityProtectionProtocol.EXTRA_PROTOCOL_VERSION,
                AccessibilityProtectionProtocol.VERSION,
            )
            .putExtra(AccessibilityProtectionProtocol.EXTRA_ENABLED, enabled)
        val resultReceiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent?) {
                val actualEnabled = getResultExtras(false)?.getBoolean(
                    AccessibilityProtectionProtocol.EXTRA_ENABLED,
                    isEnabled(context),
                ) ?: isEnabled(context)
                onResult(ControlResult(status = statusOf(resultCode), enabled = actualEnabled))
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // Android 14+ does not share the sender's identity by default, and the backend
                // accepts a request only from this app's own UID.
                val options = BroadcastOptions.makeBasic()
                    .setShareIdentityEnabled(true)
                    .toBundle()
                context.sendOrderedBroadcast(
                    intent,
                    null,
                    options,
                    resultReceiver,
                    scheduler,
                    AccessibilityProtectionProtocol.RESULT_UNAVAILABLE,
                    null,
                    null,
                )
            } else {
                // Before Android 14 a receiver cannot learn who sent a broadcast, so the backend
                // refuses the request; it comes back as UNAVAILABLE, never as a silent success.
                context.sendOrderedBroadcast(
                    intent,
                    null,
                    resultReceiver,
                    scheduler,
                    AccessibilityProtectionProtocol.RESULT_UNAVAILABLE,
                    null,
                    null,
                )
            }
        } catch (_: RuntimeException) {
            scheduler.post {
                onResult(
                    ControlResult(
                        status = ControlStatus.UNAVAILABLE,
                        enabled = isEnabled(context),
                    ),
                )
            }
        }
    }

    data class ControlResult(
        val status: ControlStatus,
        val enabled: Boolean,
    )

    enum class ControlStatus {
        APPLIED,
        UNAVAILABLE,
        REJECTED,
    }
}
