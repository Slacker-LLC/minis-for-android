package com.openminis.app.ui.chat

import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import com.openminis.app.runtime.files.WorkspaceFileClient
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ShareBufferEffect(sessionId: kotlin.String, context: android.content.Context, viewModel: com.openminis.app.ui.chat.ChatViewModel, inputTextState: androidx.compose.runtime.State<kotlin.String>, shareBufferVersionState: androidx.compose.runtime.State<kotlin.Int>) {
    val inputText by inputTextState
    val shareBufferVersion by shareBufferVersionState
    androidx.compose.runtime.LaunchedEffect(shareBufferVersion) {
        if (shareBufferVersion == 0) return@LaunchedEffect
        val pending = com.openminis.app.share.ShareCoordinator.consumeBuffer(context)
            ?: return@LaunchedEffect
        com.openminis.app.logging.AppLogger.info(
            "ChatScreen",
            "[Share] injecting ${pending.items.size} item(s) into chat session=$sessionId",
        )
        val sharedDir = com.openminis.app.share.SharedShareStore.sharedFileDirectory(context)
        // [T-android-share-buffer-merge] Accumulate locally rather than
        // reading `inputText` inside the loop. `inputText` is captured from
        // composition and does NOT observe the setInputText calls made here,
        // so every text item was appended to the same stale base and only the
        // last one survived — a two-text share landed as just the second one
        // even after the store-level merge delivered both.
        var draft = inputText
        var failedAttachments = 0
        for (item in pending.items) {
            when (item.kind) {
                com.openminis.app.share.PendingShare.Item.Kind.INLINE_TEXT -> {
                    val sep = if (draft.isNotEmpty()) "\n" else ""
                    val needsTrailingSpace = item.value.startsWith("http://") ||
                        item.value.startsWith("https://")
                    draft = draft + sep + item.value +
                        if (needsTrailingSpace) " " else ""
                    viewModel.setInputText(draft)
                }
                com.openminis.app.share.PendingShare.Item.Kind.ATTACHMENT -> {
                    if (viewModel.addAttachmentFromStagedShare(java.io.File(sharedDir, item.value)) == null) {
                        failedAttachments++
                    }
                }
            }
        }
        viewModel.markShareInjected()
        // Only this share's own files: another share may be mid-copy or awaiting confirmation.
        com.openminis.app.share.SharedShareStore.deleteSharedFiles(context, pending.attachmentFileNames())
        if (failedAttachments > 0) {
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.share_attach_failed_toast),
                android.widget.Toast.LENGTH_LONG,
            ).show()
        }
    }
}

@Composable
internal fun SessionDisposableEffect(sessionId: kotlin.String, context: android.content.Context, viewModel: com.openminis.app.ui.chat.ChatViewModel, attachmentsState: androidx.compose.runtime.State<kotlin.collections.List<com.openminis.app.ui.chat.InputAttachment>>, tHangDiagAppContext: android.content.Context) {
    val attachments by attachmentsState
    androidx.compose.runtime.DisposableEffect(sessionId) {
        ChatViewModelStore.setActiveSession(sessionId)
        // [T-HANG-DIAG] enter / dispose markers around the ChatScreen lifetime
        // so we can correlate "user tapped session X" → loadSession timings
        // and any subsequent hang record. Removable by grepping out
        // `[T-HANG-DIAG]` from this file.
        println(
            "[T-HANG-DIAG] ChatScreen MOUNT session=$sessionId hangCount=" +
                com.openminis.app.diagnostics.HangDetector.currentHangCount(tHangDiagAppContext),
        )
        com.openminis.app.diagnostics.PerfLongCtx.step(sessionId, "chatScreen.mount")
        onDispose {
            println("[T-HANG-DIAG] ChatScreen UNMOUNT session=$sessionId")
            ChatViewModelStore.clearActiveSession(sessionId)
            // T-android-new-chat-empty-residue: drop sessions materialised by
            // a settings toggle (ensureSession via /memory, /thinking, etc.)
            // but never sent a real message. VM guards on streaming + DB count
            // so an in-flight agent or non-empty session is left alone.
            // Skip cleanup on configuration changes (e.g. rotation) — the
            // composable is about to re-mount with the same session and its
            // pending attachments would be lost if we released the ViewModel.
            val activity = context as? android.app.Activity
            if (activity?.isChangingConfigurations != true) {
                viewModel.cleanupIfEmptyOnExit()
            }
        }
    }

    // Hang-detector quiet-period reset: if the user lands on a chat session
    // and stays for 10s without the watchdog firing again, the previous
    // hang count was a transient blip and the breaker can release. The call
    // itself is cheap — early-returns when the count is already zero.
}

@Composable
internal fun SessionLaunchEffect(sessionId: kotlin.String, context: android.content.Context, htmlPreviewHolderState: androidx.compose.runtime.MutableState<com.openminis.app.ui.preview.WebViewHolder?>, htmlPreviewFallbackTitleState: androidx.compose.runtime.MutableState<kotlin.String>, htmlPreviewFullscreenState: androidx.compose.runtime.MutableState<kotlin.Boolean>, appCtx: android.content.Context) {
    var htmlPreviewHolder by htmlPreviewHolderState
    var htmlPreviewFallbackTitle by htmlPreviewFallbackTitleState
    var htmlPreviewFullscreen by htmlPreviewFullscreenState
    LaunchedEffect(sessionId) {
        val pending = com.openminis.app.deeplink.DeepLinkCoordinator
            .pendingHtmlPreview.value ?: return@LaunchedEffect
        if (pending.sessionId != sessionId) return@LaunchedEffect
        com.openminis.app.deeplink.DeepLinkCoordinator.consumePendingHtmlPreview()
        val absPath = "/var/minis" + pending.resourcePath
        val file = withContext(Dispatchers.IO) {
            val name = pending.resourcePath.substringAfterLast('/')
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
            val digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest("${pending.sessionId}:$absPath".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(java.util.Locale.US, it) }
            val staged = File(File(context.cacheDir, "pinned-html"), "$digest-$name")
            runCatching {
                WorkspaceFileClient.readToFile(pending.sessionId, absPath, staged)
                staged.takeIf { it.isFile }
            }.getOrNull()
        }
        if (file == null) {
            com.openminis.app.logging.AppLogger.warning(
                "ChatScreen",
                "pinned HTML preview path missing: $absPath",
            )
            return@LaunchedEffect
        }
        val url = "file://${file.absolutePath}"
        htmlPreviewHolder?.destroy()
        htmlPreviewHolder = com.openminis.app.ui.preview.WebViewHolder(appCtx, url)
        htmlPreviewFallbackTitle = pending.title
        htmlPreviewFullscreen = true
    }
}
