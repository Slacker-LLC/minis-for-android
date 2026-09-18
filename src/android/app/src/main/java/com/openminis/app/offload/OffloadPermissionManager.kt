package com.openminis.app.offload

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import org.json.JSONArray
import org.json.JSONObject

/**
 * Manages offload permissions for privacy-sensitive agent tools.
 * Three levels: BYPASS (auto-allow), ASK_ONCE (per-session), NOT_ALLOWED.
 *
 * Mirrors iOS OffloadPermissionManager behavior.
 */
object OffloadPermissionManager {

    enum class PermissionLevel {
        BYPASS,      // Always allowed
        ASK_ONCE,    // Ask once per session
        NOT_ALLOWED, // Always denied
    }

    data class PermissionRequest(
        val toolName: String,
        val toolTitle: String,
        val description: String,
        val sessionId: String,
    )

    enum class PermissionCategory(val displayName: String) {
        PRIVACY("Privacy"),
        MEDIA("Media"),
        SYSTEM("System"),
        // T330: privileged automation CLIs (Shizuku binder / Accessibility
        // service). These run via the offload bridge as shell tools, not as
        // named LLM tool calls, so the gate happens inside the
        // NativeOffloadHandler entry point rather than ChatViewModel.
        INTEGRATIONS("Integrations"),
    }

    data class ToolPermissionInfo(
        val toolName: String,
        val displayName: String,
        val category: PermissionCategory,
        val defaultLevel: PermissionLevel,
        /**
         * Mirrors iOS `OffloadCommandInfo.showInSettings`. When false the tool
         * is registered (so the dialog/check pipeline still recognises its
         * name) but hidden from the Permissions settings page — Media + System
         * tools carry no personal data and have no reason to clutter the UI.
         */
        val showInSettings: Boolean = true,
    )

    /**
     * Registry of all tools and their permission categories. Defaults are
     * BYPASS across the board to mirror iOS — the user opted into running an
     * agent app, so background access is permitted unless they explicitly
     * downgrade an entry to ASK_ONCE / NOT_ALLOWED. Tools omitted from this
     * registry (e.g. the former `open_url` and `model_use` entries) fall
     * through to BYPASS via [getLevel]'s unknown-tool branch — no separate
     * "always permit" carve-out needed.
     */
    val toolRegistry: List<ToolPermissionInfo> = listOf(
        // Privacy — user-configurable, visible in Settings.
        ToolPermissionInfo("calendar", "Calendar", PermissionCategory.PRIVACY, PermissionLevel.BYPASS),
        ToolPermissionInfo("location", "Location", PermissionCategory.PRIVACY, PermissionLevel.BYPASS),
        ToolPermissionInfo("clipboard", "Clipboard", PermissionCategory.PRIVACY, PermissionLevel.BYPASS),
        ToolPermissionInfo("contacts", "Contacts", PermissionCategory.PRIVACY, PermissionLevel.BYPASS),
        ToolPermissionInfo("photos", "Photos", PermissionCategory.PRIVACY, PermissionLevel.BYPASS),
        // Media — no personal data, hidden from Settings.
        ToolPermissionInfo("speak", "Text-to-Speech", PermissionCategory.MEDIA, PermissionLevel.BYPASS, showInSettings = false),
        ToolPermissionInfo("media_player", "Media Player", PermissionCategory.MEDIA, PermissionLevel.BYPASS, showInSettings = false),
        ToolPermissionInfo("speech_recognition", "Speech Recognition", PermissionCategory.MEDIA, PermissionLevel.BYPASS, showInSettings = false),
        // System — no personal data, hidden from Settings.
        ToolPermissionInfo("alarm", "Alarms & Timers", PermissionCategory.SYSTEM, PermissionLevel.BYPASS, showInSettings = false),
        ToolPermissionInfo("weather", "Weather", PermissionCategory.SYSTEM, PermissionLevel.BYPASS, showInSettings = false),
        ToolPermissionInfo("notification", "Notifications", PermissionCategory.SYSTEM, PermissionLevel.BYPASS, showInSettings = false),
        ToolPermissionInfo("device_info", "Device Info", PermissionCategory.SYSTEM, PermissionLevel.BYPASS, showInSettings = false),
        // T330: integrations — opt-in by default. These tools can drive
        // other apps and read on-screen content, so the safer posture is
        // NOT_ALLOWED until the user picks otherwise even when the
        // underlying system layer (Shizuku binder / Accessibility service)
        // is already authorized.
        ToolPermissionInfo("a11y_cli", "android-a11y-cli", PermissionCategory.INTEGRATIONS, PermissionLevel.NOT_ALLOWED),
        ToolPermissionInfo("shizuku_cli", "android-shizuku-cli", PermissionCategory.INTEGRATIONS, PermissionLevel.NOT_ALLOWED),
    )

    /** Stable session-id used by NativeOffloadHandlers when calling
     *  [checkPermission] from the offload IPC thread. The shell-CLI gate
     *  (T330) lives outside ChatViewModel.executeTool, so there's no
     *  per-chat-session id available to scope ASK_ONCE grants. We use
     *  one process-lifetime grant slot instead — the user "Always Allow"
     *  upgrades naturally to BYPASS via [respondToRequest]. */
    const val OFFLOAD_GLOBAL_SESSION_ID = "offload-global"

    private lateinit var prefs: SharedPreferences

    /** Session-scoped grants for ASK_ONCE tools. Populated by the
     *  "Allow in this session" dialog response. Cleared when the
     *  hosting session ends (or process death — see
     *  OFFLOAD_GLOBAL_SESSION_ID for offload-CLI-backed tools, which
     *  share one process-lifetime slot). */
    private val sessionGrants = mutableMapOf<String, MutableSet<String>>() // sessionId -> set of toolNames

    /** T338: session-scoped denials. Populated by the "Deny in this
     *  session" dialog response. Once a tool is in here for a given
     *  session, [checkPermission] returns false without re-prompting
     *  — prevents the agent from spamming the user with the same
     *  request after they already said no. Cleared with
     *  [clearSessionGrants]. */
    private val sessionDenials = mutableMapOf<String, MutableSet<String>>() // sessionId -> set of toolNames

    /** Active permission request waiting for user response. */
    private val _pendingRequest = MutableStateFlow<PermissionRequest?>(null)
    val pendingRequest: StateFlow<PermissionRequest?> = _pendingRequest.asStateFlow()

    private var pendingContinuation: kotlin.coroutines.Continuation<Response>? = null

    // ── Android system runtime permission request (for location etc.) ──────────

    /** Result of a runtime permission or settings-gate flow. */
    enum class AndroidPermissionResult {
        GRANTED,
        DENIED,
        TIMEOUT,
        /**
         * Nobody can answer: no Activity is started, so the dialog cannot be
         * shown and the request would only sit until it timed out. Reported
         * straight away — see [setPermissionHostAttached].
         */
        NO_UI,
    }

    /**
     * True while the Activity that owns the permission launchers is started
     * (`MainActivity.onStart` / `onStop`). The system dialog and the in-app
     * settings prompt can only come from that host, so a request raised
     * without one is answered with [AndroidPermissionResult.NO_UI]
     * immediately instead of waiting the timeouts out.
     *
     * The device pass is why this exists: a permission-gated CLI invoked with
     * the app off screen waited the full budget for a dialog nobody could
     * see, and the caller learned only `command timed out after 120000ms`.
     */
    @Volatile
    var permissionHostAttached: Boolean = false
        private set

    fun setPermissionHostAttached(attached: Boolean) {
        permissionHostAttached = attached
    }

    /** Timeout for a single system permission dialog round-trip. */
    const val SYSTEM_DIALOG_TIMEOUT_MS: Long = 120_000L

    /** Timeout for the "bounce the user to a settings page" flow. */
    const val SETTINGS_GATE_TIMEOUT_MS: Long = 120_000L

    /**
     * Budget for the whole interactive gate — system dialog, post-DENY grant
     * poll and the settings trip together. Each stage on its own allows two
     * minutes, so a gate could spend 120 s + 5 s + 120 s and still be running
     * when the guest CLI's own command timeout (measured at 120 s on the
     * device: `command timed out after 120000ms`) cut the call short — the
     * structured result never reached the caller. Bounding the gate keeps
     * [permissionFailure] inside the caller's window.
     */
    const val PERMISSION_FLOW_BUDGET_MS: Long = 90_000L

    data class AndroidPermissionRequest(val permissions: List<String>)

    private val _pendingAndroidPermission = MutableStateFlow<AndroidPermissionRequest?>(null)
    val pendingAndroidPermission: StateFlow<AndroidPermissionRequest?> = _pendingAndroidPermission.asStateFlow()

    private var androidPermissionContinuation: kotlin.coroutines.Continuation<AndroidPermissionResult>? = null

    /**
     * Ask the UI layer to drive the system runtime-permission flow for the
     * given permissions. The UI is responsible for:
     *
     *  - Detecting whether the permission has already been permanently denied
     *    (so the system dialog would be a no-op). If so, responding with
     *    [AndroidPermissionResult.DENIED] so the caller can fall back to the
     *    in-app "go to settings" flow via [requestSettingsGate].
     *  - Otherwise launching the `RequestMultiplePermissions` contract and
     *    returning the result.
     *
     * The call is suspended until the UI responds or [SYSTEM_DIALOG_TIMEOUT_MS]
     * elapses.
     */
    suspend fun requestAndroidPermission(permissions: List<String>): AndroidPermissionResult {
        if (!permissionHostAttached) return AndroidPermissionResult.NO_UI
        val timed = withTimeoutOrNull(SYSTEM_DIALOG_TIMEOUT_MS) {
            suspendCancellableCoroutine<AndroidPermissionResult> { cont ->
                androidPermissionContinuation = cont
                _pendingAndroidPermission.value = AndroidPermissionRequest(permissions)
                cont.invokeOnCancellation {
                    _pendingAndroidPermission.value = null
                    androidPermissionContinuation = null
                }
            }
        }
        if (timed == null) {
            // Timed out — clear the pending request so the UI doesn't fire a
            // stale permission dialog later.
            _pendingAndroidPermission.value = null
            androidPermissionContinuation = null
            return AndroidPermissionResult.TIMEOUT
        }
        return timed
    }

    /** Called from UI after the system permission dialog returns. */
    fun respondToAndroidPermission(result: AndroidPermissionResult) {
        _pendingAndroidPermission.value = null
        androidPermissionContinuation?.resume(result)
        androidPermissionContinuation = null
    }

    /** Overload kept for callers that only have a granted/denied bool. */
    fun respondToAndroidPermission(granted: Boolean) =
        respondToAndroidPermission(
            if (granted) AndroidPermissionResult.GRANTED else AndroidPermissionResult.DENIED
        )

    // ── In-app "go to settings" gate ───────────────────────────────────────────

    /**
     * Describes a gate we can't resolve with the standard permission dialog:
     * either the user has already permanently denied the runtime permission,
     * or the capability (e.g. Notification Access) requires a trip to a
     * system settings page.
     */
    data class SettingsGateRequest(
        /** Stable id for the gate (e.g. "POST_NOTIFICATIONS" or "notification_access"). */
        val id: String,
        /** Title shown in the in-app AlertDialog. */
        val title: String,
        /** Body shown in the in-app AlertDialog. */
        val message: String,
        /** Intent action used to open the appropriate settings page. */
        val settingsAction: String,
        /**
         * Set when the target settings page is "Application details" and the
         * Intent must include a `package:<pkg>` data URI.
         */
        val requiresPackageUri: Boolean,
        /** "Allow" button label for the in-app dialog. */
        val positiveLabel: String = "Open Settings",
        /** "Cancel" button label for the in-app dialog. */
        val negativeLabel: String = "Cancel",
    )

    /** What the UI actor decided after the dialog closed. */
    enum class SettingsGateDecision { OPEN, CANCEL }

    private val _pendingSettingsGate = MutableStateFlow<SettingsGateRequest?>(null)
    val pendingSettingsGate: StateFlow<SettingsGateRequest?> = _pendingSettingsGate.asStateFlow()

    private var settingsGateContinuation: kotlin.coroutines.Continuation<SettingsGateDecision>? = null

    /**
     * Show an in-app dialog explaining why a settings trip is needed, then
     * (if the user accepts) wait up to [SETTINGS_GATE_TIMEOUT_MS] polling
     * [check] for success.
     *
     * Returns:
     *  - [AndroidPermissionResult.GRANTED] if [check] succeeds within the
     *    polling window.
     *  - [AndroidPermissionResult.DENIED] if the user cancels the dialog.
     *  - [AndroidPermissionResult.TIMEOUT] if the user accepts but doesn't
     *    complete the grant within the window.
     */
    suspend fun requestSettingsGate(
        request: SettingsGateRequest,
        check: () -> Boolean,
    ): AndroidPermissionResult {
        if (!permissionHostAttached) return AndroidPermissionResult.NO_UI
        val decision = suspendCancellableCoroutine<SettingsGateDecision> { cont ->
            settingsGateContinuation = cont
            _pendingSettingsGate.value = request
            cont.invokeOnCancellation {
                _pendingSettingsGate.value = null
                settingsGateContinuation = null
            }
        }
        if (decision == SettingsGateDecision.CANCEL) return AndroidPermissionResult.DENIED

        val granted = withTimeoutOrNull(SETTINGS_GATE_TIMEOUT_MS) {
            while (true) {
                if (check()) return@withTimeoutOrNull true
                delay(500L)
            }
            @Suppress("UNREACHABLE_CODE") false
        }
        return when (granted) {
            true -> AndroidPermissionResult.GRANTED
            else -> AndroidPermissionResult.TIMEOUT
        }
    }

    /**
     * Poll [check] every [intervalMs] for up to [timeoutMs] after a DENIED
     * result from [requestAndroidPermission]. Catches the "grant propagation
     * race" — the system dialog returned before the PackageManager fully
     * committed the grant, or the user was still mid-tap when DENIED fired.
     */
    suspend fun pollForPermissionGrant(
        check: () -> Boolean,
        timeoutMs: Long = 5_000L,
        intervalMs: Long = 500L,
    ): Boolean {
        val granted = withTimeoutOrNull(timeoutMs) {
            while (true) {
                if (check()) return@withTimeoutOrNull true
                delay(intervalMs)
            }
            @Suppress("UNREACHABLE_CODE") false
        }
        return granted == true
    }

    /** Called from UI after the in-app "go to settings" dialog closes. */
    fun respondToSettingsGate(decision: SettingsGateDecision) {
        _pendingSettingsGate.value = null
        settingsGateContinuation?.resume(decision)
        settingsGateContinuation = null
    }

    /**
     * The single permission-gate round trip every caller shares: system
     * dialog, one post-DENY grant poll (the dialog can return before the
     * grant propagates), then the in-app settings trip when the user said no
     * or the dialog cannot re-appear. Bounded by [PERMISSION_FLOW_BUDGET_MS].
     *
     * Callers only render [permissionFailure] when the result is not
     * [AndroidPermissionResult.GRANTED]. Six guest handlers and two in-app
     * tools had each copy-pasted this sequence with their own wording, which
     * is how the unattended case ended up reporting a bare timeout.
     */
    suspend fun requestPermissionFlow(
        permissions: List<String>,
        satisfied: () -> Boolean,
        settingsGate: SettingsGateRequest,
    ): AndroidPermissionResult {
        val outcome = withTimeoutOrNull(PERMISSION_FLOW_BUDGET_MS) {
            var result = requestAndroidPermission(permissions)
            if (result == AndroidPermissionResult.DENIED && pollForPermissionGrant(satisfied)) {
                result = AndroidPermissionResult.GRANTED
            }
            if (result == AndroidPermissionResult.DENIED) {
                result = requestSettingsGate(settingsGate, check = satisfied)
            }
            result
        }
        // The inner stages clear whatever the UI was showing when they are
        // cancelled, so an expired budget only has to be reported: naming the
        // permission beats another bare "timed out".
        return outcome ?: AndroidPermissionResult.TIMEOUT
    }

    /**
     * Failure body for a permission gate, or null when the gate was granted.
     * Names the permission and what the user has to do, so an agent (or a
     * person reading the transcript) does not have to guess why the call
     * stopped. Pure, so the wording is pinned by a unit test.
     *
     * [permissions] is the list of Android permission names, or a capability
     * label when the grant does not come from one (Notification access has no
     * runtime permission — it lives on its own settings page). [deniedCode]
     * keeps a capability-specific code where one exists, because agents act on
     * the difference between "ask the user again" and "this is not a runtime
     * permission at all".
     */
    fun permissionFailure(
        tool: String,
        permissions: List<String>,
        result: AndroidPermissionResult,
        detail: String? = null,
        deniedCode: String = "permission_denied",
    ): JSONObject? {
        if (result == AndroidPermissionResult.GRANTED) return null
        val names = permissions.joinToString(", ")
        val error = when (result) {
            AndroidPermissionResult.NO_UI -> "permission_required"
            AndroidPermissionResult.DENIED -> deniedCode
            AndroidPermissionResult.TIMEOUT -> "permission_timeout"
            AndroidPermissionResult.GRANTED -> return null
        }
        val message = when (result) {
            AndroidPermissionResult.NO_UI ->
                "Minis is not on screen, so the prompt for $names cannot be shown. " +
                    "Open Minis, grant it, then retry."
            AndroidPermissionResult.DENIED ->
                "The user declined $names; $tool cannot run until it is granted."
            AndroidPermissionResult.TIMEOUT ->
                "No answer to the $names prompt within " +
                    "${PERMISSION_FLOW_BUDGET_MS / 1000} s. The prompt may still be on " +
                    "screen — ask the user before retrying."
            AndroidPermissionResult.GRANTED -> return null
        }
        val body = JSONObject()
            .put("error", error)
            .put("tool", tool)
            .put("permissions", JSONArray(permissions))
            .put("message", if (detail.isNullOrBlank()) message else "$message $detail")
        if (!detail.isNullOrBlank()) body.put("detail", detail)
        return body
    }

    /**
     * Android's `shouldShowRequestPermissionRationale` returns false in two
     * cases: (a) the app has never asked, and (b) the user permanently
     * denied. We track (a) ourselves so the UI can distinguish them.
     */
    fun hasAskedForPermission(context: Context, permission: String): Boolean {
        val p = context.getSharedPreferences("offload_permissions_asked", Context.MODE_PRIVATE)
        return p.getBoolean(permission, false)
    }

    fun markPermissionAsked(context: Context, permission: String) {
        context.getSharedPreferences("offload_permissions_asked", Context.MODE_PRIVATE)
            .edit().putBoolean(permission, true).apply()
    }

    fun init(context: Context) {
        prefs = context.getSharedPreferences("offload_permissions", Context.MODE_PRIVATE)
    }

    fun getLevel(toolName: String): PermissionLevel {
        val info = toolRegistry.find { it.toolName == toolName }
            ?: return PermissionLevel.BYPASS // Unknown tools are bypassed

        val stored = prefs.getString("level_$toolName", null)
        return if (stored != null) {
            try { PermissionLevel.valueOf(stored) } catch (_: Exception) { info.defaultLevel }
        } else {
            info.defaultLevel
        }
    }

    fun setLevel(toolName: String, level: PermissionLevel) {
        prefs.edit().putString("level_$toolName", level.name).apply()
    }

    fun resetAll() {
        val editor = prefs.edit()
        for (tool in toolRegistry) {
            editor.remove("level_${tool.toolName}")
        }
        editor.apply()
    }

    /**
     * T338: dialog response shape. Replaces the older
     * `(allowed: Boolean, alwaysAllow: Boolean)` pair, which encoded
     * "Always Allow" as a persistent BYPASS upgrade. The new model is
     * strictly session-scoped — the user goes to Settings → Permissions
     * to make a permanent change.
     *
     *   ALLOW_SESSION  — grant for the rest of this session, no more
     *                    prompts for this tool until session ends.
     *   ALLOW_ONCE     — grant just this call; next call re-prompts.
     *   DENY_SESSION   — deny + remember the deny so the agent can't
     *                    keep nagging. Cleared with the session.
     */
    enum class Response { ALLOW_SESSION, ALLOW_ONCE, DENY_SESSION }

    /**
     * Check permission for a tool in the given session.
     * For ASK_ONCE, suspends until user responds via the dialog.
     * Returns true if allowed.
     */
    suspend fun checkPermission(toolName: String, toolTitle: String, sessionId: String): Boolean {
        val level = getLevel(toolName)
        return when (level) {
            PermissionLevel.BYPASS -> true
            PermissionLevel.NOT_ALLOWED -> false
            PermissionLevel.ASK_ONCE -> {
                // T338: a prior "Deny in this session" short-circuits
                // before any grants check or dialog so the agent can't
                // spam the user.
                val denials = sessionDenials.getOrPut(sessionId) { mutableSetOf() }
                if (toolName in denials) return false

                val grants = sessionGrants.getOrPut(sessionId) { mutableSetOf() }
                if (toolName in grants) return true

                // Show dialog and wait for response.
                val info = toolRegistry.find { it.toolName == toolName }
                val response = suspendCancellableCoroutine<Response> { cont ->
                    pendingContinuation = cont
                    _pendingRequest.value = PermissionRequest(
                        toolName = toolName,
                        toolTitle = toolTitle,
                        description = "Allow ${info?.displayName ?: toolName} access?",
                        sessionId = sessionId,
                    )
                    cont.invokeOnCancellation {
                        _pendingRequest.value = null
                        pendingContinuation = null
                    }
                }

                when (response) {
                    Response.ALLOW_SESSION -> {
                        grants.add(toolName)
                        true
                    }
                    Response.ALLOW_ONCE -> true  // no caching; next call re-prompts
                    Response.DENY_SESSION -> {
                        denials.add(toolName)
                        false
                    }
                }
            }
        }
    }

    /** Called from UI when user responds to the permission dialog. */
    fun respondToRequest(response: Response) {
        _pendingRequest.value = null
        pendingContinuation?.resume(response)
        pendingContinuation = null
    }

    /** Clear session grants AND denials (call when a session ends). */
    fun clearSessionGrants(sessionId: String) {
        sessionGrants.remove(sessionId)
        sessionDenials.remove(sessionId)
    }
}
