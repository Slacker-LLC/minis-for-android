package com.openminis.app.ui.vscreen

import android.app.ActivityOptions
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * The home screen of the virtual display: an app grid on a plain wallpaper, like the physical screen's
 * launcher. The system's own secondary launcher draws nothing on a virtual display, so Minis brings its
 * own. It is started on the virtual display by the service (when the display opens and on Home), never on
 * the physical one: a start on display 0 closes it at once, so it can never stand in for the real launcher.
 */
class VirtualScreenHomeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Activity.getDisplay() is API 30; before that the window manager's default display is the one this
        // activity is on.
        val displayId = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            display?.displayId ?: 0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.displayId
        }
        if (displayId == 0) {
            finish()
            return
        }
        setContent { Desktop(displayId) { launch(it, displayId) } }
    }

    /**
     * Started through the Shizuku service, like every other launch on the virtual display: an app starting
     * another app from its own process is stopped by MIUI's "associated start" prompt, which would pop up
     * on the virtual screen for every icon.
     */
    private fun launch(packageName: String, displayId: Int) {
        Thread {
            val viaService = runCatching {
                com.openminis.app.tools.android.vscreen.VirtualScreenClientProvider.get(applicationContext)
                    .launch(displayId, packageName)
            }.getOrDefault(false)
            if (!viaService) runOnUiThread { launchDirect(packageName, displayId) }
        }.start()
    }

    private fun launchDirect(packageName: String, displayId: Int) {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic().apply { launchDisplayId = displayId }
        runCatching { startActivity(intent, options.toBundle()) }
    }
}

private data class DesktopApp(val label: String, val packageName: String, val icon: Bitmap?)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Desktop(displayId: Int, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<DesktopApp>>(emptyList()) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            // Flags 0: MATCH_DEFAULT_ONLY keeps only activities that also declare CATEGORY_DEFAULT, which most launcher
            // activities do not, so the grid showed a fraction of the installed apps.
            pm.queryIntentActivities(intent, 0)
                .filter { it.activityInfo.packageName != context.packageName }
                .distinctBy { it.activityInfo.packageName }
                .map { info ->
                    DesktopApp(
                        label = runCatching { info.loadLabel(pm).toString() }.getOrDefault(info.activityInfo.packageName),
                        packageName = info.activityInfo.packageName,
                        icon = runCatching { info.loadIcon(pm).toBitmap(144, 144) }.getOrNull(),
                    )
                }
                .sortedBy { it.label.lowercase() }
        }
    }
    BackHandler(enabled = true) { /* the desktop is the bottom of the display: Back does nothing here */ }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF243B55), Color(0xFF141E30)))),
    ) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
            Spacer(Modifier.height(28.dp))
            var now by remember { mutableStateOf(Date()) }
            LaunchedEffect(Unit) {
                while (true) {
                    kotlinx.coroutines.delay(15_000)
                    now = Date()
                }
            }
            Text(
                text = DateFormat.getTimeInstance(DateFormat.SHORT).format(now),
                color = Color.White,
                fontSize = 44.sp,
                modifier = Modifier.padding(start = 8.dp),
            )
            Text(
                text = DateFormat.getDateInstance(DateFormat.FULL).format(now),
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 14.sp,
                modifier = Modifier.padding(start = 8.dp, bottom = 20.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                items(apps, key = { it.packageName }) { app ->
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onOpen(app.packageName) }.padding(4.dp),
                    ) {
                        app.icon?.let {
                            Image(bitmap = it.asImageBitmap(), contentDescription = app.label, modifier = Modifier.size(56.dp))
                        } ?: Spacer(Modifier.size(56.dp))
                        Text(
                            text = app.label,
                            color = Color.White,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
            }
        }
    }
}
