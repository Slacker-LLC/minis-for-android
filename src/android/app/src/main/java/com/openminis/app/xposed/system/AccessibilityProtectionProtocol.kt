package com.openminis.app.xposed.system

import android.net.Uri
import android.os.Bundle

/**
 * [T-eta-xposed-groups] The smallest contract between the app and the accessibility-protection
 * backend that lives in system_server.
 *
 * Ported from Eta `agent/accessibility/AccessibilityProtectionProtocol.kt` (Mangi-11/Eta @
 * c15de97); attribution in THIRD_PARTY_LICENSES.md. The control broadcast is validated by a
 * signature permission, the protocol version and the sender's UID together, and the health provider
 * answers the system UID only. Nothing in this contract gives the app the ability to write secure
 * settings: the backend owns that, and the app can only ask it to.
 */
object AccessibilityProtectionProtocol {
    const val VERSION = 1

    // Names follow the app's own package (see ModuleTargets.OWN_PACKAGE): a request addressed to
    // another install's permission or provider would be answered by that install instead.
    const val ACTION_SET = "llc.slacker.minis.action.SET_ACCESSIBILITY_PROTECTION"
    const val ACTION_RECOVER = "llc.slacker.minis.action.RECOVER_ACCESSIBILITY_SERVICE"
    const val PERMISSION = "llc.slacker.minis.permission.CONTROL_ACCESSIBILITY_PROTECTION"
    const val RECEIVER_PACKAGE = "android"

    const val EXTRA_PROTOCOL_VERSION = "protocol_version"
    const val EXTRA_ENABLED = "enabled"

    const val RESULT_UNAVAILABLE = 0
    const val RESULT_APPLIED = 1
    const val RESULT_REJECTED = 2

    const val SETTING_NAME = "minis_accessibility_protection_enabled"

    /**
     * Off until the user turns it on: a backend that rewrites a secure setting must never be
     * something an installed module does by itself.
     */
    const val DEFAULT_ENABLED = false

    const val HEALTH_AUTHORITY = "llc.slacker.minis.accessibility.health"
    const val HEALTH_METHOD = "accessibility_health"
    const val HEALTH_STATUS = "status"
    const val HEALTH_STATUS_CONNECTED = "connected"
    const val HEALTH_STATUS_DISCONNECTED = "disconnected"
    const val HEALTH_STATUS_REJECTED = "rejected"

    val HEALTH_URI: Uri = Uri.parse("content://$HEALTH_AUTHORITY")

    fun request(): Bundle = Bundle().apply {
        putInt(EXTRA_PROTOCOL_VERSION, VERSION)
    }

    fun hasSupportedVersion(bundle: Bundle?): Boolean =
        bundle?.getInt(EXTRA_PROTOCOL_VERSION, -1) == VERSION

    fun healthResult(status: String): Bundle = Bundle().apply {
        putInt(EXTRA_PROTOCOL_VERSION, VERSION)
        putString(HEALTH_STATUS, status)
    }
}
