package com.openminis.app.ui.settings

import androidx.annotation.StringRes
import com.openminis.app.R

/** Pure mapping: probe codes stay observable while each receives actionable localized copy. */
internal object VirtualScreenCopyPolicy {
    private val reasons: Map<String, Int> = mapOf(
        "android_supported" to R.string.vscreen_reason_android_ready,
        "android_version_unsupported" to R.string.vscreen_reason_android_old,
        "shizuku_shell_user_service" to R.string.vscreen_reason_shizuku_ready,
        "shizuku_not_ready" to R.string.vscreen_reason_shizuku_missing,
        "shizuku_unavailable" to R.string.vscreen_reason_shizuku_missing,
        "permission_denied" to R.string.vscreen_reason_shizuku_missing,
        "root_user_service_refused" to R.string.vscreen_reason_wrong_identity,
        "unexpected_user_service_uid" to R.string.vscreen_reason_wrong_identity,
        "shell_context_ready" to R.string.vscreen_reason_context_ready,
        "shell_context_unavailable" to R.string.vscreen_reason_context_failed,
        "virtual_display_created" to R.string.vscreen_reason_display_ready,
        "virtual_display_create_failed" to R.string.vscreen_reason_display_failed,
        "virtual_display_invalid_id" to R.string.vscreen_reason_display_failed,
        "probe_display_busy" to R.string.vscreen_reason_display_busy,
        "display_unavailable" to R.string.vscreen_reason_display_failed,
        "display_ime_local" to R.string.vscreen_reason_ime_ready,
        "ime_policy_unavailable" to R.string.vscreen_reason_ime_unavailable,
        "uiautomation_connected" to R.string.vscreen_reason_uiautomation_ready,
        "uiautomation_unavailable" to R.string.vscreen_reason_uiautomation_failed,
        "input_injected" to R.string.vscreen_reason_input_ready,
        "input_injection_failed" to R.string.vscreen_reason_input_failed,
        "settings_window_on_virtual_display" to R.string.vscreen_reason_launch_ready,
        "app_launch_failed" to R.string.vscreen_reason_launch_failed,
        "app_left_virtual_display" to R.string.vscreen_reason_app_moved,
        "non_black_screenshot" to R.string.vscreen_reason_screenshot_ready,
        "screenshot_unavailable" to R.string.vscreen_reason_screenshot_failed,
        "screenshot_black" to R.string.vscreen_reason_screenshot_blocked,
        "screenshot_blocked_secure" to R.string.vscreen_reason_screenshot_blocked,
        "probe_display_released" to R.string.vscreen_reason_cleanup_ready,
        "no_probe_display" to R.string.vscreen_reason_cleanup_skipped,
        "hidden_api_unavailable" to R.string.vscreen_reason_hidden_api,
        "timeout" to R.string.vscreen_reason_timeout,
        "vscreen_timeout" to R.string.vscreen_reason_service_timeout,
        "probe_step_failed" to R.string.vscreen_reason_generic_failure,
        "vscreen_probe_failed" to R.string.vscreen_reason_generic_failure,
        "vscreen_probe_required" to R.string.vscreen_reason_probe_required,
    )

    private val steps: Map<String, Int> = mapOf(
        "environment" to R.string.vscreen_step_environment,
        "shizuku" to R.string.vscreen_step_shizuku,
        "context" to R.string.vscreen_step_context,
        "virtual_display" to R.string.vscreen_step_display,
        "ime" to R.string.vscreen_step_ime,
        "uiautomation" to R.string.vscreen_step_uiautomation,
        "input" to R.string.vscreen_step_input,
        "launch" to R.string.vscreen_step_launch,
        "screenshot" to R.string.vscreen_step_screenshot,
        "cleanup" to R.string.vscreen_step_cleanup,
    )

    @StringRes fun reason(code: String): Int = reasons[code] ?: R.string.vscreen_reason_unknown
    @StringRes fun step(id: String): Int = steps[id] ?: R.string.vscreen_step_unknown
    @StringRes fun status(status: String): Int = when (status) {
        "pass" -> R.string.vscreen_status_pass
        "warning" -> R.string.vscreen_status_warning
        "fail" -> R.string.vscreen_status_fail
        "skipped" -> R.string.vscreen_status_skipped
        else -> R.string.vscreen_status_unknown
    }

    /** Where a failed check can be fixed, so the result dialog can offer a single "go there" button. */
    enum class FailAction { NONE, SHIZUKU, DEVELOPER_OPTIONS }

    fun actionFor(code: String, xiaomi: Boolean): FailAction = when (code) {
        "shizuku_not_ready", "shizuku_unavailable", "permission_denied", "unexpected_user_service_uid",
        "root_user_service_refused" -> FailAction.SHIZUKU
        "input_injection_failed" -> if (xiaomi) FailAction.DEVELOPER_OPTIONS else FailAction.NONE
        else -> FailAction.NONE
    }

    fun knownReasonCodes(): Set<String> = reasons.keys
    fun knownStepIds(): Set<String> = steps.keys

    /** Probe reports returned by V1, including app-side failure and skipped codes. */
    fun requiredProbeReasonCodes(): Set<String> = setOf(
        "android_supported", "android_version_unsupported", "shizuku_shell_user_service",
        "shizuku_not_ready", "shizuku_unavailable", "permission_denied", "root_user_service_refused",
        "unexpected_user_service_uid", "shell_context_ready", "shell_context_unavailable",
        "virtual_display_created", "virtual_display_create_failed", "virtual_display_invalid_id",
        "probe_display_busy", "display_unavailable", "display_ime_local", "ime_policy_unavailable",
        "uiautomation_connected", "uiautomation_unavailable", "input_injected", "input_injection_failed",
        "settings_window_on_virtual_display", "app_launch_failed", "app_left_virtual_display",
        "non_black_screenshot", "screenshot_unavailable", "screenshot_black", "screenshot_blocked_secure",
        "probe_display_released", "no_probe_display", "hidden_api_unavailable", "timeout",
        "probe_step_failed", "vscreen_probe_failed", "vscreen_probe_required", "vscreen_timeout",
    )
}
