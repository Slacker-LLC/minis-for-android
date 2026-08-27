package com.openminis.app.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.browser.BrowserUrlPolicy

/**
 * A composition-wide callback for opening a URL inside the app's WebView sheet.
 *
 * - For http/https links, callers should invoke this to trigger [UrlPreviewSheet]
 *   and keep the user in-app (iOS-style preview).
 * - For other schemes (mailto:, tel:, geo:, minis://, etc.) fall back to
 *   [openExternalUrl] which dispatches a normal system Intent.
 *
 * The root [InAppBrowserHost] provides this and renders the sheet when invoked.
 * If a screen reads the ambient outside a host, the default is a no-op because
 * it has no Context with which to launch an Activity.
 */
val LocalInAppBrowserLauncher = compositionLocalOf<(String) -> Unit> {
    { _ -> }
}

/**
 * Fire a system ACTION_VIEW intent for schemes we don't preview in-app or when
 * the user explicitly requests the system browser. Invalid links and resolver
 * failures are converted to stable user-facing messages instead of leaking
 * platform exceptions or silently doing nothing.
 */
fun openExternalUrl(context: Context, url: String) {
    if (BrowserUrlPolicy.classify(url) == BrowserUrlPolicy.Kind.INVALID) {
        Toast.makeText(context, "Invalid link.", Toast.LENGTH_SHORT).show()
        return
    }

    val intent = Intent(Intent.ACTION_VIEW, url.trim().toUri()).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, "No app available to open this link.", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        AppLogger.warning("InAppBrowserLauncher", "ACTION_VIEW failed: ${e.message}")
        Toast.makeText(context, "Unable to open this link.", Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun InAppBrowserHost(
    context: Context,
    content: @Composable () -> Unit,
) {
    var previewUrl by remember { mutableStateOf<String?>(null) }

    val launcher = remember(context) {
        { url: String ->
            val lower = url.trim().lowercase()
            if (
                (lower.startsWith("http://") || lower.startsWith("https://")) &&
                BrowserUrlPolicy.classify(url) == BrowserUrlPolicy.Kind.INTERNAL
            ) {
                previewUrl = url.trim()
            } else {
                openExternalUrl(context, url)
            }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalInAppBrowserLauncher provides launcher,
    ) {
        content()
    }

    previewUrl?.let { url ->
        UrlPreviewSheet(
            url = url,
            onDismiss = { previewUrl = null },
        )
    }
}
