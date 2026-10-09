package com.openminis.app.ui.components

import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import coil.compose.AsyncImage
import kotlinx.coroutines.launch

/**
 * One image in an [ImageGalleryViewer]. [model] is anything Coil's
 * `AsyncImage` accepts (Uri, File, String URL, etc.); [caption] is the
 * filename/alt-text shown in the bottom capsule, hidden when blank.
 */
data class ImageGalleryItem(
    val model: Any,
    val caption: String? = null,
)

/**
 * Fullscreen swipeable gallery — mirrors the iOS MessageImageGallery
 * (src/ios/Views/Chat/Media/MessageImageGallery.swift): HorizontalPager
 * over pinch-zoom/pan pages, immersive dialog chrome (system bars hidden,
 * tap toggles), bottom caption capsule, and Copy / Share / Save actions
 * bound to the currently visible page.
 *
 * Edge cases:
 *  - `items.size == 1` renders correctly (HorizontalPager with one page,
 *    no horizontal-swipe artefacts).
 *  - `startIndex` is coerced into bounds.
 *  - When a page is zoomed (`scale > 1f`), pan-pointer input consumes
 *    horizontal gestures, so the pager does not flip mid-zoom — matches
 *    iOS UIScrollView-blocks-TabView-swipe behavior.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ImageGalleryViewer(
    items: List<ImageGalleryItem>,
    startIndex: Int = 0,
    onDismiss: () -> Unit,
) {
    if (items.isEmpty()) {
        // Defensive: don't render an empty pager — just dismiss.
        DisposableEffect(Unit) {
            onDismiss()
            onDispose { }
        }
        return
    }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        // The dialog is its own window: lay it out edge to edge with clear bars and dark status icons, so the
        // page colour runs behind them like on the document previews.
        val dialogContainer = LocalView.current.parent as? android.view.ViewGroup
        val lightIcons = !com.openminis.app.ui.theme.ChatColors.isDark
        DisposableEffect(dialogContainer) {
            val win = dialogContainer?.let { findDialogWindowForGallery(it) }
            win?.let { w ->
                WindowCompat.setDecorFitsSystemWindows(w, false)
                @Suppress("DEPRECATION")
                w.statusBarColor = android.graphics.Color.TRANSPARENT
                @Suppress("DEPRECATION")
                w.navigationBarColor = android.graphics.Color.TRANSPARENT
                val ctrl = WindowInsetsControllerCompat(w, w.decorView)
                ctrl.isAppearanceLightStatusBars = lightIcons
                ctrl.isAppearanceLightNavigationBars = lightIcons
            }
            onDispose { }
        }

        val pagerState = rememberPagerState(
            initialPage = startIndex.coerceIn(0, items.size - 1),
            pageCount = { items.size },
        )
        // How far the picture has been dragged toward closing; the page colour fades with it.
        var dismissProgress by remember { mutableFloatStateOf(0f) }
        val currentItem = items.getOrNull(pagerState.currentPage) ?: items[0]
        val pageColor = com.openminis.app.ui.settings.settingsPageBackground()

        androidx.compose.foundation.layout.Column(
            modifier = Modifier
                .fillMaxSize()
                .background(pageColor.copy(alpha = 1f - 0.85f * dismissProgress)),
        ) {
            // The same top bar as a document preview: back, the file's name, share.
            com.openminis.app.ui.settings.MinisTopBar(
                title = {
                    Text(
                        text = currentItem.caption.orEmpty(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    )
                },
                onBack = onDismiss,
                backLabel = stringResource(R.string.filebrowser_title),
                background = pageColor,
            )
            // Clipped: a zoomed picture must not paint over the bars.
            Box(modifier = Modifier.weight(1f).clipToBounds()) {
                // Per-page pointer input (pinch / pan / double-tap) wins over the pager's swipe while zoomed.
                HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                    GalleryPage(
                        item = items[page],
                        onTapChrome = {},
                        onDismiss = onDismiss,
                        onDismissProgress = { dismissProgress = it },
                    )
                }
            }
            val savedToAlbumMsg = stringResource(R.string.image_saved_to_album_toast)
            val saveFailedMsg = stringResource(R.string.image_save_failed_toast)
            var sharing by remember { mutableStateOf(false) }
            com.openminis.app.ui.sandbox.PreviewActionBar(
                listOf(
                    com.openminis.app.ui.sandbox.PreviewAction(
                        Icons.Outlined.ContentCopy,
                        stringResource(R.string.image_action_copy),
                    ) { copyBitmapToClipboard(context, scope, currentItem.model) },
                    com.openminis.app.ui.sandbox.PreviewAction(
                        Icons.Outlined.Share,
                        stringResource(R.string.image_action_share),
                    ) {
                        if (!sharing) {
                            sharing = true
                            scope.launch {
                                try {
                                    shareImage(context, currentItem.model)
                                } finally {
                                    sharing = false
                                }
                            }
                        }
                    },
                    com.openminis.app.ui.sandbox.PreviewAction(
                        Icons.Outlined.Download,
                        stringResource(R.string.image_action_save),
                    ) {
                        scope.launch {
                            val bmp = loadBitmap(context, currentItem.model)
                            if (bmp != null) {
                                val saved = saveToGallery(context, bmp)
                                com.openminis.app.ui.components.MinisToast.show(context, if (saved) savedToAlbumMsg else saveFailedMsg)
                            }
                        }
                    },
                ),
            )
        }
    }
}

/**
 * One page inside [ImageGalleryViewer]. Owns its own zoom/pan state so the
 * pager remembers per-page transform independently — flipping to the next
 * page resets the previous page's zoom when its composition leaves.
 */
@Composable
private fun GalleryPage(
    item: ImageGalleryItem,
    onTapChrome: () -> Unit,
    onDismiss: () -> Unit,
    onDismissProgress: (Float) -> Unit,
) {
    ZoomableImagePage(
        model = item.model,
        onTap = onTapChrome,
        onDismiss = onDismiss,
        onDismissProgress = onDismissProgress,
    )
}

/**
 * Same shape as the FullscreenImageViewer.findDialogWindow walker —
 * duplicated here (private in the other file) to avoid making it a
 * public utility before we know which other components need it.
 */
private fun findDialogWindowForGallery(root: android.view.ViewGroup): android.view.Window? {
    var p: android.view.ViewParent? = root.parent
    while (p != null) {
        if (p is android.view.View) {
            val ctx = p.context
            if (ctx is android.app.Activity) return ctx.window
        }
        p = (p as? android.view.View)?.parent
    }
    return null
}
