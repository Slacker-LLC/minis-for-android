package com.openminis.app.ui.components

import android.content.Context
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.atomic.AtomicInteger

/**
 * Light feedback in the board's form: a small rounded capsule near the bottom that fades after a
 * moment and never takes a tap. Anything that needs a reason or a next step is a Banner; anything
 * that needs a decision is an Alert.
 *
 * Callers use [show] exactly where they used `Toast.makeText(...).show()`. While no [MinisToastHost]
 * is on screen (a background service, an activity without the host) it falls back to the system
 * Toast, so a message is never lost.
 */
object MinisToast {
    private val events = MutableSharedFlow<CharSequence>(extraBufferCapacity = 8)
    private val hosts = AtomicInteger(0)

    internal fun flow() = events
    internal fun hostAttached() { hosts.incrementAndGet() }
    internal fun hostDetached() { hosts.decrementAndGet() }

    fun show(context: Context, text: CharSequence) {
        if (hosts.get() > 0 && events.tryEmit(text)) return
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    fun show(context: Context, @StringRes textRes: Int) = show(context, context.getString(textRes))
}

/** Renders [MinisToast] messages. Put one at the root of the main window. */
@Composable
fun MinisToastHost() {
    var current by remember { mutableStateOf<CharSequence?>(null) }
    var visible by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        MinisToast.hostAttached()
        onDispose { MinisToast.hostDetached() }
    }
    LaunchedEffect(Unit) {
        MinisToast.flow().collect { text ->
            current = text
            visible = true
            delay(1800)
            visible = false
        }
    }
    Box(
        modifier = Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 96.dp),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(visible = visible, enter = fadeIn(), exit = fadeOut()) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                shadowElevation = 6.dp,
                tonalElevation = 0.dp,
            ) {
                Text(
                    text = current?.toString().orEmpty(),
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp),
                    maxLines = 3,
                )
            }
        }
    }
}
