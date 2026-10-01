package com.openminis.app.ui.chat

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import com.openminis.app.R
import com.openminis.app.ui.components.MinisIcons
import com.openminis.app.ui.theme.ChatColors

/** What one assistant reply can do; the action row under the newest reply and the long-press menu share it. */
internal class AssistantActionSet(
    val onCopy: () -> Unit,
    val onCopyMarkdown: () -> Unit,
    val onSelectText: () -> Unit,
    val onToggleSpeak: () -> Unit,
    val onRegenerate: () -> Unit,
    val onBranch: () -> Unit,
    val onShare: () -> Unit,
    val onDelete: () -> Unit,
)

/**
 * The long-press menu of a reply, as in the redesign: the page behind goes soft (blurred and veiled), the
 * reply stands in a white card, and the actions are listed under it with a line icon on the right.
 * Copy / Select text / Read aloud / Regenerate / Branch from here, then Share and Delete.
 */
@Composable
internal fun AssistantMessageMenu(
    previewText: String,
    isSpeaking: Boolean,
    actions: AssistantActionSet,
    onDismiss: () -> Unit,
    usage: ReplyUsage? = null,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        BlurBehindDialog()
        val scrim = if (ChatColors.isDark) Color.Black.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.78f)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(scrim)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Column {
                // The reply itself, lifted: a white card with a soft shadow.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(18.dp, RoundedCornerShape(20.dp), ambientColor = Color.Black.copy(alpha = 0.12f), spotColor = Color.Black.copy(alpha = 0.16f))
                        .clip(RoundedCornerShape(20.dp))
                        .background(if (ChatColors.isDark) ChatColors.secondaryBg else Color.White)
                        .pointerInput(Unit) { detectTapGestures { } }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = previewText,
                        fontSize = 17.sp,
                        lineHeight = 27.2.sp,
                        color = ChatColors.primaryText,
                        maxLines = 10,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Column(
                    modifier = Modifier
                        .width(240.dp)
                        .shadow(18.dp, RoundedCornerShape(14.dp), ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.14f))
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (ChatColors.isDark) ChatColors.secondaryBg else Color.White),
                ) {
                    fun pick(action: () -> Unit): () -> Unit = { onDismiss(); action() }
                    MenuRow(stringResource(R.string.assistant_action_copy), MinisIcons.Copy, onClick = pick(actions.onCopy))
                    MenuRow(stringResource(R.string.assistant_menu_select_text), MinisIcons.TextSelect, onClick = pick(actions.onSelectText))
                    MenuRow(
                        stringResource(if (isSpeaking) R.string.assistant_action_stop_reading else R.string.assistant_action_read_aloud),
                        if (isSpeaking) MinisIcons.StopCircle else MinisIcons.Volume,
                        onClick = pick(actions.onToggleSpeak),
                    )
                    MenuRow(stringResource(R.string.assistant_action_regenerate), MinisIcons.Refresh, onClick = pick(actions.onRegenerate))
                    MenuRow(stringResource(R.string.assistant_menu_branch_here), MinisIcons.Branch, onClick = pick(actions.onBranch))
                    HorizontalDivider(thickness = 6.dp, color = if (ChatColors.isDark) Color.Black else Color(0xFFF2F2F7))
                    MenuRow(stringResource(R.string.assistant_menu_copy_markdown), MinisIcons.Code, onClick = pick(actions.onCopyMarkdown))
                    MenuRow(stringResource(R.string.assistant_menu_share), MinisIcons.Share, onClick = pick(actions.onShare))
                    MenuRow(stringResource(R.string.assistant_menu_delete), MinisIcons.Trash, destructive = true, onClick = pick(actions.onDelete), last = true)
                }
                usage?.let { UsageCaption(it) }
            }
        }
    }
}

@Composable
private fun MenuRow(label: String, icon: ImageVector, destructive: Boolean = false, last: Boolean = false, onClick: () -> Unit) {
    val color = if (destructive) MaterialTheme.colorScheme.error else ChatColors.primaryText
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).height(46.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, fontSize = 16.sp, color = color, modifier = Modifier.weight(1f))
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
        }
        if (!last) HorizontalDivider(thickness = 0.5.dp, color = ChatColors.separator.copy(alpha = 0.5f), modifier = Modifier.padding(start = 16.dp))
    }
}

/** Blur what is behind this dialog's window (Android 12+ when the device allows it). */
@Composable
private fun BlurBehindDialog(radius: Int = 40) {
    val view = LocalView.current
    val blurAllowed = remember(view) {
        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            (view.context.getSystemService(WindowManager::class.java)?.isCrossWindowBlurEnabled == true)
    }
    if (!blurAllowed) return
    val provider = remember(view) {
        var parent: android.view.ViewParent? = view.parent
        while (parent != null) {
            if (parent is DialogWindowProvider) return@remember parent
            parent = (parent as? android.view.View)?.parent
        }
        null
    }
    DisposableEffect(provider) {
        val window = provider?.window ?: return@DisposableEffect onDispose {}
        window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        // blurAllowed above already means API 31+; the check is repeated where lint can see it.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            runCatching { window.setBackgroundBlurRadius(radius) }
        }
        onDispose {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                runCatching { window.setBackgroundBlurRadius(0) }
            }
            window.clearFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
        }
    }
}

/**
 * The reply's token usage and finish time as a quiet footnote under the menu — "ctx:57k in:2 out:408
 * cache:57k (96%)  22:30". Counts only; the time is its own Text, pushed to the far edge, so it does not
 * read as one more number. Style follows OpenMinis 1.14's usage capsule.
 */
@Composable
private fun UsageCaption(reply: ReplyUsage) {
    val summary = remember(reply) { usageSummary(reply.usage) }
    val clock = remember(reply) {
        reply.completedAtMs?.let { java.text.SimpleDateFormat("HH:mm", java.util.Locale.US).format(java.util.Date(it)) }
    }
    val ink = ChatColors.secondaryText
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.padding(top = 10.dp, start = 4.dp).fillMaxWidth(),
    ) {
        Icon(Icons.Default.Speed, contentDescription = null, tint = ink, modifier = Modifier.size(12.dp))
        // The counts give way first when the line is long (cache figures), so the finish time stays visible.
        Text(
            summary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        clock?.let {
            Text(it, fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = ink, maxLines = 1, softWrap = false)
        }
    }
}
