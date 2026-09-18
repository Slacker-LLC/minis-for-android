package com.openminis.app.accessibility

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.openminis.app.xposed.system.AccessibilityProtectionProtocol

/**
 * [T-eta-xposed-groups] The smallest possible answer to the module's health question: is this app's
 * accessibility service connected right now?
 *
 * Ported from Eta `agent/accessibility/AgentAccessibilityHealthProvider.kt` (Mangi-11/Eta @
 * c15de97); attribution in THIRD_PARTY_LICENSES.md. The backend inside system_server needs to know
 * whether a service that is enabled in the settings is actually alive - the only process that can
 * answer that is this one. The platform already lets only the system query it
 * (`android.permission.MANAGE_ACCESSIBILITY`), and the call itself is re-checked against the
 * system UID, the protocol version and the method name; the answer is one word, with no nodes, no
 * windows and no user content in it.
 */
class MinisAccessibilityHealthProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (Binder.getCallingUid() != Process.SYSTEM_UID ||
            method != AccessibilityProtectionProtocol.HEALTH_METHOD ||
            !AccessibilityProtectionProtocol.hasSupportedVersion(extras)
        ) {
            return AccessibilityProtectionProtocol.healthResult(
                AccessibilityProtectionProtocol.HEALTH_STATUS_REJECTED,
            )
        }
        return AccessibilityProtectionProtocol.healthResult(
            if (MinisAccessibilityService.getInstance() != null) {
                AccessibilityProtectionProtocol.HEALTH_STATUS_CONNECTED
            } else {
                AccessibilityProtectionProtocol.HEALTH_STATUS_DISCONNECTED
            },
        )
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
