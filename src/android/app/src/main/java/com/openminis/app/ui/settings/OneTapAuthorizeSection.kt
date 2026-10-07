package com.openminis.app.ui.settings

import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.permissions.RuntimePermissionGranter
import kotlinx.coroutines.launch

/**
 * One switch for the whole permission chain, on the System & permissions page next to the Agent permission
 * list (not inside it): on = grant every Android permission the app declares (root, one go) and allow every
 * Agent tool, including the integrations that default to off, so the two sides cannot disagree. Off puts the
 * Agent tools back to their defaults; the Android grants stay (revoke those in the system settings).
 */
@Composable
fun OneTapAuthorizeSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var epoch by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val on = remember(epoch) {
        RuntimePermissionGranter.allGranted(context) && OffloadPermissionManager.isEverythingAllowed()
    }
    SettingsSection(header = stringResource(R.string.oneclick_header)) {
        SettingsSwitchRow(
            icon = Icons.Outlined.VerifiedUser,
            iconColor = Color(0xFF34C759),
            title = stringResource(R.string.oneclick_title),
            subtitle = stringResource(if (busy) R.string.oneclick_working else R.string.oneclick_sub),
            checked = on,
            enabled = !busy,
            showDivider = false,
            onCheckedChange = { turnOn ->
                if (!turnOn) {
                    OffloadPermissionManager.resetAll()
                    epoch++
                    return@SettingsSwitchRow
                }
                busy = true
                scope.launch {
                    val result = RuntimePermissionGranter.grantAll(context)
                    // The Agent side is opened whatever the Android side managed: a phone without root still
                    // gets its Agent tools allowed, and the message says which grants are missing.
                    OffloadPermissionManager.allowAll()
                    busy = false
                    epoch++
                    val message = when {
                        result.unavailable != null -> context.getString(R.string.perm_grant_all_no_root)
                        result.failed.isNotEmpty() -> context.getString(
                            R.string.perm_grant_all_failed, result.failed.joinToString(", "),
                        )
                        else -> context.getString(R.string.perm_grant_all_done, result.succeeded, result.attempted)
                    }
                    Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                }
            },
        )
    }
}
