package com.openminis.app.ui.chat

import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import java.io.File
import kotlinx.coroutines.launch

@Composable
internal fun rememberPerformSendOrEnqueue(viewModel: com.openminis.app.ui.chat.ChatViewModel, lastSendTimeMsState: androidx.compose.runtime.MutableState<kotlin.Long>, noteSendForInputModePref: kotlin.Function0<kotlin.Unit>, coroutineScope: kotlinx.coroutines.CoroutineScope, tracedScrollToItem: suspend (kotlin.String, kotlin.Int, kotlin.Int) -> kotlin.Unit, isNearBottom: androidx.compose.runtime.State<kotlin.Boolean>, userScrolledAwayState: androidx.compose.runtime.MutableState<kotlin.Boolean>, releaseComposerAfterSend: kotlin.Function0<kotlin.Unit>): kotlin.Function2<kotlin.String, PendingDelivery, kotlin.Unit> {
    var lastSendTimeMs by lastSendTimeMsState
    var userScrolledAway by userScrolledAwayState
    return handler@{ rawText, delivery ->
        if (viewModel.tryExecuteInputAsSlashCommand(rawText)) {
            viewModel.setInputText("")
            releaseComposerAfterSend()
            return@handler
        }
        lastSendTimeMs = System.currentTimeMillis()
        // [T-android-slash-send-keeps-text] A send always ends the slash session.
        //
        // Tapping the "/" button over existing text puts the composer into
        // "over-content" mode: it prepends "/ " (so "hello" becomes "/ hello")
        // and stashes the original in savedInputBeforeSlash so every exit path
        // can restore it. But SEND was not one of those exit paths — it cleared
        // the text while leaving the stash and the open menu behind, so the
        // just-sent body was restored into the composer and the user saw their
        // message both sent AND still sitting in the input.
        //
        // Clearing the session here, before the text is cleared, makes send a
        // proper terminal exit: nothing is left to restore. The dismiss and
        // command-row paths keep their own restore behaviour untouched.
        viewModel.endSlashSessionForSend()
        viewModel.setInputText("")
        releaseComposerAfterSend()
        viewModel.sendMessage(rawText, delivery)
        noteSendForInputModePref()
        userScrolledAway = false
        coroutineScope.launch {
            tracedScrollToItem("SEND-PATH/initial", 0, 0)
            kotlinx.coroutines.delay(100)
            tracedScrollToItem("SEND-PATH/settle", 0, 0)
        }
    }
    // T196: timestamp of the last drag-stop. The streaming auto-follow LE
    // below has three stages (initial scroll → re-pin after frame → settle
    // after 220 ms) any of which can fire on the *next* token after a drag
    // ends. When the user drags down while already near the bottom,
    // userScrolledAway never flips true, so without this grace window the
    // three stages all run on the next chunk and the user sees the chat
    // "jump back" three times. 1 s covers a typical fling settle (~500-
    // 800 ms) plus a small buffer; the user can re-engage follow at any
    // time by scrolling all the way to the bottom (LE(isNearBottom) above
    // resets userScrolledAway).
}

@Composable
internal fun rememberUrlClickHandler(onPreviewAttachment: kotlin.Function1<com.openminis.app.ui.sandbox.FileItem, kotlin.Unit>, context: android.content.Context, viewModel: com.openminis.app.ui.chat.ChatViewModel, attachmentsState: androidx.compose.runtime.State<kotlin.collections.List<com.openminis.app.ui.chat.InputAttachment>>, coroutineScope: kotlinx.coroutines.CoroutineScope, previewUrlState: androidx.compose.runtime.MutableState<kotlin.String?>, previewImageGalleryState: androidx.compose.runtime.MutableState<kotlin.Pair<kotlin.collections.List<com.openminis.app.ui.components.ImageGalleryItem>, kotlin.Int>?>): kotlin.Function1<kotlin.String, kotlin.Unit> {
    val attachments by attachmentsState
    var previewUrl by previewUrlState
    var previewImageGallery by previewImageGalleryState
    return remember<(String) -> Unit>(viewModel, coroutineScope) {
        { url ->
            coroutineScope.launch {
                // Pass the current session id so `minis://attachments/...` resolves
                // against this chat's session directory rather than whichever
                // session booted its guest shell most recently (which is what
                // the global bindMounts map would answer).
                when (val action = ChatLinkResolver.resolveAsync(url, viewModel.currentSessionId, context)) {
                    is ChatLinkAction.DeepLink -> ChatLinkResolver.dispatchDeepLink(context, url)
                    is ChatLinkAction.SandboxFile -> {
                        when {
                            action.item.isImageFile -> {
                                // Single image — caption = filename. Sibling
                                // collection from markdown context is not
                                // plumbed here (iOS does cross-session
                                // assistant images via fingerprint).
                                previewImageGallery = listOf(
                                    com.openminis.app.ui.components.ImageGalleryItem(
                                        model = action.item.file,
                                        caption = action.item.name,
                                    ),
                                ) to 0
                            }
                            // T279: route through the NavHost FILE_PREVIEW destination
                            // (same path as user-bubble attachments and "Browse Chat Files")
                            // so FilePreviewScreen inherits the Activity's edge-to-edge
                            // window setup. The previous in-place Dialog wrapper had
                            // its own Window without enableEdgeToEdge, painting the
                            // platform default scrim on the status / nav bars.
                            else -> onPreviewAttachment(action.item)
                        }
                    }
                    is ChatLinkAction.ExternalApp ->
                        com.openminis.app.ui.browser.BrowserExternalSchemeHandler
                            .handle(context, action.url)
                    is ChatLinkAction.Web -> previewUrl = action.url
                }
            }
        }
    }
}

@Composable
internal fun rememberMarkdownImageTapHandler(sessionId: kotlin.String, context: android.content.Context, messagesState: androidx.compose.runtime.State<kotlin.collections.List<com.openminis.app.ui.chat.ChatMessage>>, coroutineScope: kotlinx.coroutines.CoroutineScope, previewImageGalleryState: androidx.compose.runtime.MutableState<kotlin.Pair<kotlin.collections.List<com.openminis.app.ui.components.ImageGalleryItem>, kotlin.Int>?>, urlClickHandler: kotlin.Function1<kotlin.String, kotlin.Unit>): kotlin.Function2<kotlin.String, kotlin.String, kotlin.Unit> {
    val messages by messagesState
    var previewImageGallery by previewImageGalleryState
    return remember<(String, String) -> Unit>(messages, sessionId, coroutineScope) {
        { tappedMessageId, tappedUrl ->
            coroutineScope.launch {
            val imageRegex = Regex("!\\[([^\\]]*)\\]\\(([^)\\s]+)\\)")
            data class Ref(val messageId: String, val source: String, val title: String)
            val refs = mutableListOf<Ref>()
            for (msg in messages) {
                if (msg.role != "assistant") continue
                val content = msg.content
                if (content.isEmpty()) continue
                for (m in imageRegex.findAll(content)) {
                    val alt = m.groupValues.getOrNull(1).orEmpty()
                    val src = m.groupValues.getOrNull(2).orEmpty()
                    if (src.isEmpty()) continue
                    val pathPart = src.substringBefore('?').substringBefore('#')
                    val ext = pathPart.substringAfterLast('.', "").lowercase()
                    // Skip non-image media so the gallery stays still-image only,
                    // matching iOS minisVideoExtensions / minisAudioExtensions.
                    if (ext in setOf("mp4", "mov", "avi", "mkv", "webm",
                                     "mp3", "wav", "aac", "flac", "ogg", "m4a")) continue
                    val title = alt.ifEmpty { pathPart.substringAfterLast('/').ifEmpty { src } }
                    refs.add(Ref(msg.id, src, title))
                }
            }
            if (refs.isEmpty()) {
                // Defensive: tap arrived for a URL that isn't in the visible
                // window (compacted away, just deleted, etc.). Fall back to
                // the single-item URL handler so the user still sees the
                // tapped image rather than swallowing the tap silently.
                    urlClickHandler(tappedUrl)
                    return@launch
            }
            val startIndex = refs.indexOfFirst { it.messageId == tappedMessageId && it.source == tappedUrl }
                .takeIf { it >= 0 }
                ?: refs.indexOfFirst { it.source == tappedUrl }.takeIf { it >= 0 }
                ?: 0
            val items = refs.map { ref ->
                // Resolve minis://... / file:// / /abs → host File so Coil
                // doesn't have to re-walk RuntimePathRegistry for every page swipe.
                // For an owned minis:// URL, a miss stays a miss instead of
                // falling through to the global, sessionless fetcher.
                val resolved = resolveMdMediaFile(context, ref.source, sessionId)
                val model = if (
                    resolved == null &&
                    ref.source.substringBefore('?').startsWith("minis://")
                ) {
                    java.io.File(context.cacheDir, ".missing-minis-media/${ref.source.hashCode()}")
                } else {
                    resolved ?: ref.source
                }
                com.openminis.app.ui.components.ImageGalleryItem(
                    model = model,
                    caption = ref.title,
                )
            }
            previewImageGallery = items to startIndex
            }
        }
    }
}
