package com.openminis.app.ui.components

import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * One image in a full-screen viewer: pinch to zoom, drag to pan while zoomed, double-tap to zoom, tap for the
 * chrome, and, when it is not zoomed, drag it down (or up) to close the viewer. The picture follows the finger,
 * the backdrop fades through [onDismissProgress] (0 = at rest, 1 = about to go), and a long enough drag or a
 * quick flick closes it; letting go early springs it back. A sideways drag at rest is left alone so a pager
 * around this page still flips between images.
 */
@Composable
internal fun ZoomableImagePage(
    model: Any,
    onTap: () -> Unit,
    onDismiss: () -> Unit,
    onDismissProgress: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var dragY by remember { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    val closeDistance = with(density) { 120.dp.toPx() }
    val currentDismiss by rememberUpdatedState(onDismiss)
    val currentProgress by rememberUpdatedState(onDismissProgress)

    fun progress(): Float = (abs(dragY) / (screenHeightPx * 0.4f)).coerceIn(0f, 1f)

    Box(modifier = modifier.fillMaxSize()) {
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val shrink = 1f - 0.2f * progress()
                    scaleX = scale * shrink
                    scaleY = scale * shrink
                    translationX = offsetX
                    translationY = offsetY + dragY
                }
                .pointerInput(Unit) {
                    val slop = viewConfiguration.touchSlop
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val velocity = VelocityTracker().also { it.addPosition(down.uptimeMillis, down.position) }
                        var total = Offset.Zero
                        // undecided -> a zoom/pan gesture, a vertical close drag, or a sideways drag we leave alone
                        var mode = GestureMode.UNDECIDED
                        do {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val pressed = event.changes.count { it.pressed }
                            if (pressed >= 2 || mode == GestureMode.TRANSFORM) {
                                mode = GestureMode.TRANSFORM
                                val zoom = event.calculateZoom()
                                val pan = event.calculatePan()
                                scale = (scale * zoom).coerceIn(1f, 8f)
                                if (scale > 1f) {
                                    offsetX += pan.x
                                    offsetY += pan.y
                                } else {
                                    offsetX = 0f
                                    offsetY = 0f
                                }
                                event.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
                            } else if (scale > 1f) {
                                // zoomed in: one finger pans the picture and keeps the pager still
                                val pan = event.calculatePan()
                                offsetX += pan.x
                                offsetY += pan.y
                                event.changes.forEach { if (it.positionChange() != Offset.Zero) it.consume() }
                            } else {
                                val change = event.changes.firstOrNull { it.pressed } ?: break
                                velocity.addPosition(change.uptimeMillis, change.position)
                                val delta = change.positionChange()
                                total += delta
                                if (mode == GestureMode.UNDECIDED && total.getDistance() > slop) {
                                    mode = if (abs(total.y) > abs(total.x)) GestureMode.CLOSE else GestureMode.SIDEWAYS
                                }
                                if (mode == GestureMode.CLOSE) {
                                    dragY += delta.y
                                    currentProgress(progress())
                                    change.consume()
                                }
                            }
                        } while (event.changes.any { it.pressed })

                        if (mode == GestureMode.CLOSE) {
                            val flick = abs(velocity.calculateVelocity().y)
                            if (abs(dragY) > closeDistance || flick > FLICK_PX_PER_SECOND) {
                                currentDismiss()
                            } else {
                                val from = dragY
                                scope.launch {
                                    animate(from, 0f) { value, _ ->
                                        dragY = value
                                        currentProgress(progress())
                                    }
                                }
                            }
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = {
                            if (scale > 1f) {
                                scale = 1f; offsetX = 0f; offsetY = 0f
                            } else {
                                scale = 2.5f
                            }
                        },
                        onTap = { onTap() },
                    )
                },
        )
    }
}

private enum class GestureMode { UNDECIDED, TRANSFORM, CLOSE, SIDEWAYS }

private const val FLICK_PX_PER_SECOND = 1800f
