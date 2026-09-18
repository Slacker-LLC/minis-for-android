package com.openminis.app.runtime.ubuntu

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Ported from Eta `agent/device/RootAccess.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md.
 *
 * The app's own view of whether it has root, which this repository did not have: it
 * could find `su` ([DirectRootRunner.findSu]) and run through it, but nothing held the
 * answer, so every surface that needed it either re-probed or assumed.
 *
 * Two deliberate differences from upstream:
 *  - the probe runs through [DirectRootRunner] — the same `su` launcher the runtime
 *    uses — instead of Eta's own bounded command executor, so there is one root path
 *    and not two;
 *  - [initialize] only *looks* for `su` unless a previous probe was granted. Eta asks
 *    for root on first launch; this app asks when the user does, because the runtime
 *    start already triggers the prompt for anyone who uses the Linux environment.
 */
internal enum class RootAccessStatus { UNKNOWN, UNAVAILABLE, NOT_GRANTED, GRANTED, DENIED, TIMED_OUT }

internal data class RootAccessState(
    val status: RootAccessStatus = RootAccessStatus.UNKNOWN,
    val suPresent: Boolean = false,
    val isChecking: Boolean = false,
    /**
     * [T-root-manager-detection-android] Label of the installed root manager, when su is
     * not reachable from this process. Null when su is present or no known manager is
     * installed, so the two dead ends can be told apart in the UI.
     */
    val rootManager: String? = null,
) {
    val isGranted: Boolean get() = status == RootAccessStatus.GRANTED
}

internal object RootAccess {
    private const val PREFERENCES = "minis_root_access"
    private const val AUTOMATIC_REQUEST_ATTEMPTED = "automatic_request_attempted"
    private const val LAST_GRANTED = "last_granted"
    private const val PROBE_TIMEOUT_MS = 30_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(RootAccessState())
    val state: StateFlow<RootAccessState> = mutableState.asStateFlow()
    val isGranted: Boolean get() = mutableState.value.isGranted

    private var preferences: SharedPreferences? = null
    private var probeJob: Job? = null

    /**
     * Loads what the last run knew and looks for `su`. A device that granted root
     * before is re-probed silently — the grant is remembered by the su manager, so that
     * probe does not ask the user anything.
     */
    fun initialize(context: Context) {
        val prefs = preferences(context)
        val present = DirectRootRunner.findSu() != null
        mutableState.value = RootAccessState(
            status = if (present) RootAccessStatus.NOT_GRANTED else RootAccessStatus.UNAVAILABLE,
            suPresent = present,
            rootManager = if (present) null else RootManagerDetector.detect(context)?.label,
        )
        if (present && prefs.getBoolean(LAST_GRANTED, false)) {
            refresh(context)
        }
    }

    /** Probe without asking: only runs when the policy says a prompt is not needed. */
    fun refresh(context: Context): Job = startProbe(context, explicit = false)

    /** Probe because the user asked, which may raise the su prompt. */
    fun request(context: Context): Job = startProbe(context, explicit = true)

    /** Called by an executor that saw su refuse; a plain command failure is not a denial. */
    fun markDenied() {
        preferences?.edit { putBoolean(LAST_GRANTED, false) }
        mutableState.value = mutableState.value.copy(status = RootAccessStatus.DENIED)
    }

    private fun startProbe(context: Context, explicit: Boolean): Job = synchronized(this) {
        probeJob?.takeIf { it.isActive }?.let { return@synchronized it }
        val prefs = preferences(context)
        scope.launch {
            mutableState.value = mutableState.value.copy(isChecking = true)
            try {
                val present = DirectRootRunner.findSu() != null
                if (!present) {
                    mutableState.value = RootAccessState(
                        status = RootAccessStatus.UNAVAILABLE,
                        rootManager = RootManagerDetector.detect(context)?.label,
                    )
                    return@launch
                }
                val attempted = prefs.getBoolean(AUTOMATIC_REQUEST_ATTEMPTED, false)
                val wasGranted = prefs.getBoolean(LAST_GRANTED, false)
                if (!shouldRequestRoot(explicit, attempted, wasGranted)) {
                    mutableState.value = mutableState.value.copy(
                        status = if (mutableState.value.status == RootAccessStatus.UNKNOWN) {
                            RootAccessStatus.NOT_GRANTED
                        } else {
                            mutableState.value.status
                        },
                        suPresent = true,
                    )
                    return@launch
                }
                // Remember the attempt before it happens: a process that dies during the
                // su dialog must not ask again on the next launch.
                prefs.edit { putBoolean(AUTOMATIC_REQUEST_ATTEMPTED, true) }
                val result = DirectRootRunner.runArgv(listOf("id", "-u"), timeoutMs = PROBE_TIMEOUT_MS)
                val status = rootStatusFor(
                    suPresent = true,
                    exitCode = result.exitCode,
                    stdout = result.stdout,
                    timedOut = result.timedOut,
                    error = result.error,
                )
                prefs.edit { putBoolean(LAST_GRANTED, status == RootAccessStatus.GRANTED) }
                mutableState.value = RootAccessState(status = status, suPresent = true)
            } finally {
                mutableState.value = mutableState.value.copy(isChecking = false)
            }
        }.also { probeJob = it }
    }

    private fun preferences(context: Context): SharedPreferences =
        preferences ?: context.applicationContext
            .getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .also { preferences = it }
}

/**
 * Upstream's rule: ask when the user asked, when this device has never been asked, or
 * when it answered yes before. After a refusal only an explicit request tries again.
 */
internal fun shouldRequestRoot(explicit: Boolean, attempted: Boolean, wasGranted: Boolean): Boolean =
    explicit || !attempted || wasGranted

/**
 * The probe's outcome as a state. `id -u` printing anything but 0 means the su
 * manager answered with a different identity — a denial, not a failure.
 */
internal fun rootStatusFor(
    suPresent: Boolean,
    exitCode: Int,
    stdout: String,
    timedOut: Boolean,
    error: String?,
): RootAccessStatus = when {
    !suPresent -> RootAccessStatus.UNAVAILABLE
    timedOut -> RootAccessStatus.TIMED_OUT
    error != null -> RootAccessStatus.DENIED
    exitCode != 0 -> RootAccessStatus.DENIED
    stdout.trim() == "0" -> RootAccessStatus.GRANTED
    stdout.isBlank() -> RootAccessStatus.UNKNOWN
    else -> RootAccessStatus.DENIED
}
