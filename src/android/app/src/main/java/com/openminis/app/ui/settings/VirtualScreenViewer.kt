package com.openminis.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openminis.app.R
import com.openminis.app.tools.android.AndroidPackageController
import com.openminis.app.tools.android.DeviceScreenLease
import com.openminis.app.tools.android.ScreenshotFrameRegistry
import com.openminis.app.tools.android.VirtualScreenObservationRegistry
import com.openminis.app.tools.android.vscreen.VirtualScreenClient
import com.openminis.app.tools.android.vscreen.VirtualScreenClientProvider
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.components.MinisToast
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** What the viewer has to say: switched off, switched on but unused, or a live display. */
internal enum class ViewerState { DISABLED, IDLE, LIVE }

internal fun viewerStateFor(enabled: Boolean, activeDisplayId: Int?): ViewerState = when {
    !enabled -> ViewerState.DISABLED
    activeDisplayId == null || activeDisplayId <= 0 -> ViewerState.IDLE
    else -> ViewerState.LIVE
}

private data class ViewerStatus(val state: ViewerState, val info: VirtualScreenClient.DisplayInfo?, val app: String?)

/**
 * The virtual screen in your hands: a live picture of it (every frame the display draws, not a
 * snapshot once a second), touch that goes straight to it, and the buttons to open it, close it, go
 * back or home, start an app and type into the focused field. The display stays open until you close it
 * here or the agent closes it. Opened from the chat's "..." menu and from the team page.
 */
@Composable
fun VirtualScreenViewerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val client = remember(context) { VirtualScreenClientProvider.get(context) }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<ViewerStatus?>(null) }
    var busy by remember { mutableStateOf(false) }
    var fps by remember { mutableStateOf(0) }
    var confirmClose by remember { mutableStateOf(false) }
    var showApps by remember { mutableStateOf(false) }
    var showType by remember { mutableStateOf(false) }

    // What the display is doing, read from the service itself so a display opened earlier (or by the
    // agent) is found. The picture does not depend on this loop; it only decides which screen to show.
    LaunchedEffect(Unit) {
        while (isActive) {
            val next = withContext(Dispatchers.IO) {
                val enabled = runCatching { client.isEnabled() }.getOrDefault(false)
                val info = if (enabled) runCatching { client.displayInfo() }.getOrNull() else null
                ViewerStatus(viewerStateFor(enabled, info?.id), info, client.foregroundPackageName)
            }
            status = next
            delay(1_000)
        }
    }

    val current = status
    val live = current?.state == ViewerState.LIVE
    val touchPump = remember(client) {
        VirtualScreenTouchPump { touch ->
            val id = client.displayId ?: return@VirtualScreenTouchPump
            client.touch(id, touch.action, touch.x, touch.y, touch.downTimeMs)
        }
    }
    val view = remember(context) { VirtualScreenView(context) { touchPump.post(it) } }
    DisposableEffect(Unit) { onDispose { touchPump.stop() } }

    // One stream while a display is live; stopped when it goes away or the sheet closes.
    DisposableEffect(live) {
        if (live) {
            runCatching {
                client.startFrameStream(
                    onFrame = { buffer -> try { view.submit(buffer) } finally { buffer.close() } },
                    onEnded = {},
                )
            }
        }
        onDispose { if (live) Thread { runCatching { client.stopFrameStream() } }.start() }
    }
    LaunchedEffect(live) {
        while (live && isActive) {
            delay(1_000)
            fps = view.takeFrameCount()
        }
        fps = 0
    }

    fun openDisplay() {
        if (busy) return
        busy = true
        scope.launch {
            val failure = withContext(Dispatchers.IO) {
                runCatching { client.openDisplay() }.exceptionOrNull()
            }
            busy = false
            if (failure != null) MinisToast.show(context, context.getString(R.string.vscreen_viewer_open_failed, failure.message.orEmpty().take(120)))
        }
    }

    fun closeDisplay() {
        if (busy) return
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) {
                val id = client.displayId
                runCatching { client.releaseDisplay() }
                if (id != null) {
                    VirtualScreenObservationRegistry.clearDisplay(id)
                    ScreenshotFrameRegistry.clearDisplay(id)
                }
            }
            busy = false
            status = ViewerStatus(ViewerState.IDLE, null, null)
        }
    }

    fun press(block: (Int) -> Unit) {
        val id = client.displayId ?: return
        scope.launch(Dispatchers.IO) { runCatching { block(id) } }
    }

    // A sheet that rises from the bottom at the browser's height, not a full-screen dialog.
    com.openminis.app.ui.chat.StandardChatSheet(
        title = stringResource(R.string.vscreen_viewer_title),
        onDismiss = onDismiss,
        heightFraction = 0.86f,
        containerColor = settingsSheetColor(),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (current?.state) {
                ViewerState.LIVE -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val size = current.info?.let { "${it.width}×${it.height}" }.orEmpty()
                        val app = current.app?.let { stringResource(R.string.vscreen_viewer_app, it) }
                        Text(
                            text = listOfNotNull(app, size.takeIf { it.isNotBlank() }, if (fps > 0) stringResource(R.string.vscreen_viewer_fps, fps) else null)
                                .joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        MinisTextButton(
                            onClick = {
                                val id = current.info?.id
                                if (id != null && DeviceScreenLease.shared.owner(id) != null) confirmClose = true else closeDisplay()
                            },
                            enabled = !busy,
                        ) { Text(stringResource(R.string.vscreen_viewer_close), color = MaterialTheme.colorScheme.error) }
                    }
                    Box(
                        modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(ChatColors.secondaryBg),
                        contentAlignment = Alignment.Center,
                    ) {
                        AndroidView(factory = { view }, modifier = Modifier.fillMaxSize())
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        ControlButton(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.vscreen_viewer_back)) { press { client.back(it) } }
                        ControlButton(Icons.Default.Home, stringResource(R.string.vscreen_viewer_home)) { press { client.home(it) } }
                        ControlButton(Icons.Default.Apps, stringResource(R.string.vscreen_viewer_launch)) { showApps = true }
                        ControlButton(Icons.Default.Keyboard, stringResource(R.string.vscreen_viewer_type)) { showType = true }
                    }
                }
                ViewerState.IDLE -> CenterColumn {
                    Text(stringResource(R.string.vscreen_viewer_idle), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    MinisButton(onClick = { openDisplay() }, enabled = !busy) {
                        if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(stringResource(R.string.vscreen_viewer_open))
                    }
                }
                ViewerState.DISABLED -> CenterColumn {
                    Text(stringResource(R.string.vscreen_viewer_disabled), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                null -> Unit
            }
        }
    }

    if (confirmClose) {
        MinisAlertDialog(
            onDismissRequest = { confirmClose = false },
            title = stringResource(R.string.vscreen_viewer_close),
            text = stringResource(R.string.vscreen_viewer_close_ai),
            confirmText = stringResource(R.string.vscreen_viewer_close),
            isDestructive = true,
            onConfirm = { confirmClose = false; closeDisplay() },
        )
    }
    if (showApps) {
        LaunchAppDialog(
            onDismiss = { showApps = false },
            onPick = { packageName ->
                showApps = false
                val id = client.displayId
                if (id != null) scope.launch(Dispatchers.IO) {
                    val ok = runCatching { client.launch(id, packageName) }.getOrDefault(false)
                    if (!ok) withContext(Dispatchers.Main) { MinisToast.show(context, context.getString(R.string.vscreen_viewer_launch_failed)) }
                }
            },
        )
    }
    if (showType) {
        TypeTextDialog(
            onDismiss = { showType = false },
            onSend = { text ->
                showType = false
                press { client.setText(it, text) }
            },
        )
    }
}

@Composable
private fun ControlButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CenterColumn(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}

/** Pick a launcher app to start on the virtual display. */
@Composable
private fun LaunchAppDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(query) {
        val json: JSONObject = AndroidPackageController.search(context, query.ifBlank { null }, 50)
        val array = json.optJSONArray("apps")
        apps = buildList { if (array != null) for (i in 0 until array.length()) array.optJSONObject(i)?.let { add(it.optString("label") to it.optString("package")) } }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = true)) {
        Column(
            modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.vscreen_viewer_launch), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
            SettingsSearchField(query, { query = it }, stringResource(R.string.vscreen_viewer_search_apps))
            LazyColumn(modifier = Modifier.height(320.dp)) {
                items(apps) { (label, pkg) ->
                    Column(modifier = Modifier.fillMaxWidth().clickable { onPick(pkg) }.padding(vertical = 8.dp)) {
                        Text(label, style = MaterialTheme.typography.bodyLarge)
                        Text(pkg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** Fill the field that has focus on the virtual display with text typed here. */
@Composable
private fun TypeTextDialog(onDismiss: () -> Unit, onSend: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    MinisAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.vscreen_viewer_type)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.vscreen_viewer_type_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SettingsSearchField(text, { text = it }, stringResource(R.string.vscreen_viewer_type))
            }
        },
        confirmButton = { MinisTextButton(onClick = { onSend(text) }, enabled = text.isNotEmpty()) { Text(stringResource(R.string.vscreen_viewer_type_send)) } },
        dismissButton = { MinisTextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
