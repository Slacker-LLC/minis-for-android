package com.openminis.app.ui.settings

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.openminis.app.R
import com.openminis.app.tools.android.vscreen.VirtualScreenClientProvider
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/** What the viewer has to say: switched off, switched on but unused, or a live display. */
internal enum class ViewerState { DISABLED, IDLE, LIVE }

internal fun viewerStateFor(enabled: Boolean, activeDisplayId: Int?): ViewerState = when {
    !enabled -> ViewerState.DISABLED
    activeDisplayId == null || activeDisplayId <= 0 -> ViewerState.IDLE
    else -> ViewerState.LIVE
}

private data class ViewerFrame(val state: ViewerState, val bitmap: Bitmap?, val app: String?)

/**
 * A live look at the virtual screen: what the agent is doing on it, refreshed about once a second
 * while this dialog is open. It only reads (a screenshot of the virtual display, never the physical
 * one) and never starts the display; when nothing is using it, it says so. Opened from the chat's
 * "..." menu and from the team page.
 */
@Composable
fun VirtualScreenViewerDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val client = remember(context) { VirtualScreenClientProvider.get(context) }
    var frame by remember { mutableStateOf<ViewerFrame?>(null) }

    DisposableEffect(Unit) { onDispose { frame?.bitmap?.recycle() } }

    LaunchedEffect(Unit) {
        while (isActive) {
            val next = withContext(Dispatchers.IO) {
                val enabled = client.isEnabled()
                val id = if (enabled) {
                    client.displayId ?: runCatching { client.queryActiveDisplayId() }.getOrNull()
                } else {
                    null
                }
                val state = viewerStateFor(enabled, id)
                val bitmap = if (state == ViewerState.LIVE && id != null) {
                    runCatching {
                        ParcelFileDescriptor.AutoCloseInputStream(client.screenshot(id)).use { BitmapFactory.decodeStream(it) }
                    }.getOrNull()
                } else {
                    null
                }
                ViewerFrame(state, bitmap, client.foregroundPackageName)
            }
            val previous = frame?.bitmap
            frame = next
            if (previous !== next.bitmap) previous?.recycle()
            delay(1_000)
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = ChatColors.background) {
            Column(modifier = Modifier.statusBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.vscreen_viewer_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    MinisTextButton(onClick = onDismiss) { Text(stringResource(R.string.common_close)) }
                }
                val current = frame
                when (current?.state) {
                    ViewerState.LIVE -> {
                        current.app?.let {
                            Text(
                                text = stringResource(R.string.vscreen_viewer_app, it),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                            current.bitmap?.let {
                                Image(
                                    bitmap = it.asImageBitmap(),
                                    contentDescription = stringResource(R.string.vscreen_viewer_title),
                                    contentScale = ContentScale.Fit,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                    }
                    ViewerState.IDLE -> Message(stringResource(R.string.vscreen_viewer_idle))
                    ViewerState.DISABLED -> Message(stringResource(R.string.vscreen_viewer_disabled))
                    null -> Unit
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 24.dp),
    )
}
