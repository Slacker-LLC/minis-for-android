package com.openminis.app.ui.chat

import android.net.Uri
import android.util.Log
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import java.io.File
import org.json.JSONObject

/**
 * Turns stored message rows into what the rest of the chat uses: the list shown in the UI
 * ([toChatMessages]) and the history sent to the model ([toLLMMessage]). Moved out of
 * [ChatViewModel] unchanged; it needs only the media directory, so it can be tested and reused
 * without a ViewModel.
 */
internal class ChatMessageMapper(
    private val mediaBaseDir: File,
    /** The text a model without image input gets in place of a picture, or null; read at call time. */
    private val visionPlaceholderFor: (String?) -> String?,
) {

    fun toChatMessages(rows: List<MessageEntity>, parsedParts: Map<String, Result<org.json.JSONArray>>): List<ChatMessage> =
        rows.mapToChat(parsedParts)

    fun toLLMMessage(entity: MessageEntity, parsedParts: Result<org.json.JSONArray>? = null): LLMMessage =
        entity.mapToLlm(parsedParts)

    private companion object {
        private const val TAG = "ChatMessageMapper"
        private const val LEGACY_CANCELLED_MARKER = "[cancelled by user]"
    }

    /**
     * Matches a `<system-reminder>...</system-reminder>` block, including any
     * surrounding whitespace / newlines, so a part that is *only* a reminder
     * collapses to empty text instead of leaving a blank gap. DOTALL so `.`
     * spans newlines (reminders run multi-line in the cancel/resume paths).
     *
     * Only applied at the UI-render transform — agentHistory + DB rows keep
     * the raw text so the LLM continues to see the reminder on subsequent
     * turns (matches iOS, where system-reminder text is appended to
     * agentHistory/AgentMessage parts but never to the chat-list ChatMessage).
     */
    private val systemReminderRegex =
        Regex("\\s*<system-reminder>.*?</system-reminder>\\s*", RegexOption.DOT_MATCHES_ALL)

    private fun stripSystemReminders(text: String): String =
        if (!text.contains("<system-reminder>")) text
        else systemReminderRegex.replace(text, "")

    /**
     * [T-android-retry-attachment-loss] Remove the `<user-attached-files>` XML
     * inventory from a persisted text part for DISPLAY only. The XML is now
     * persisted (iOS parity) so the model keeps the file paths across retry /
     * reload, but it must never render in the user bubble — the file chips are
     * rebuilt from the mediaRef parts instead. Mirrors the index-based strip
     * already used by editMessage / the title-fallback path.
     */
    fun stripAttachedFilesXml(text: String): String {
        val startIdx = text.indexOf("<user-attached-files>")
        if (startIdx < 0) return text
        val endTag = "</user-attached-files>"
        val endIdx = text.indexOf(endTag, startIdx)
        return if (endIdx >= 0) {
            text.substring(0, startIdx) + text.substring(endIdx + endTag.length)
        } else {
            text.substring(0, startIdx)
        }
    }

    private fun List<MessageEntity>.mapToChat(parsedParts: Map<String, Result<org.json.JSONArray>>): List<ChatMessage> {
        // First pass: extract all toolResult data keyed by toolUseId
        val toolResultMap = mutableMapOf<String, ToolResultData>()
        for (entity in this) {
            if (entity.role != "user") continue
            try {
                val array = parsedParts.getValue(entity.id).getOrThrow()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    if (obj.optString("type") == "toolResult") {
                        val value = obj.getJSONObject("value")
                        val toolUseId = value.optString("toolUseId", "")
                        if (toolUseId.isNotEmpty()) {
                            toolResultMap[toolUseId] = ToolResultData(
                                output = value.optString("output", ""),
                                success = value.optBoolean("success", true),
                            )
                        }
                    }
                }
            } catch (_: Exception) { /* skip malformed */ }
        }

        // Second pass: convert messages, merging tool results into blocks
        // Filter out user messages that only contain toolResult parts (no visible text)
        return mapNotNull { entity ->
            var text = ""
            val blocks = mutableListOf<AssistantBlock>()
            // T128: media attachments persisted under user messages as `mediaRef`
            // parts. Restored to file:// URIs (stable across app restarts) and
            // their original filenames so UserAttachmentList renders the same
            // tiles after a session reload.
            val restoredImageUris = mutableListOf<Uri>()
            val restoredAttachmentNames = mutableListOf<String>()
            // T150: file:// URIs of restored non-image attachments, in the
            // same order as the non-image suffix of restoredAttachmentNames.
            // Powers the user-bubble file chip → FilePreviewScreen tap after
            // a session reload.
            val restoredAttachmentUris = mutableListOf<Uri>()

            if (entity.role == "assistant" && !entity.reasoningContent.isNullOrEmpty()) {
                blocks.add(AssistantBlock(
                    id = "thinking_restored_${entity.id}",
                    kind = "thinking",
                    content = entity.reasoningContent,
                    toolTitle = "Thinking",
                    toolStatus = ToolBlockStatus.SUCCESS,
                ))
            }

            try {
                val array = parsedParts.getValue(entity.id).getOrThrow()
                var textBlockCounter = 0
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    when (obj.optString("type")) {
                        "text" -> {
                            val raw = obj.optString("value", "")
                            // Strip <system-reminder>...</system-reminder> blocks
                            // here only — agentHistory in memory and the DB row
                            // both keep the raw text, so the LLM still sees the
                            // reminder on subsequent turns. UI just hides it.
                            // If a part was *only* a reminder, the cleaned
                            // string is empty and we skip it so we don't render
                            // a phantom blank text block.
                            // [T-android-retry-attachment-loss] Also strip the
                            // now-persisted <user-attached-files> XML so it
                            // doesn't render in the user bubble (file chips come
                            // from mediaRef parts). The DB row + agentHistory
                            // keep the raw XML so the model still sees paths.
                            val t = stripAttachedFilesXml(stripSystemReminders(raw)).let {
                                if (it != raw) it.trim() else it
                            }
                            if (t.isEmpty()) continue
                            text += t
                            // For assistant messages, also push the text as a block so
                            // the renderer can preserve the original text↔tool ordering.
                            // For user messages we keep using the `text` field only.
                            if (entity.role == "assistant") {
                                blocks.add(AssistantBlock(
                                    id = "text_restored_${entity.id}_${textBlockCounter++}",
                                    kind = "text",
                                    content = t,
                                ))
                            }
                        }
                        "toolUse" -> {
                            val value = obj.getJSONObject("value")
                            val toolId = value.optString("toolUseId", "")
                            if (toolId.startsWith("thinking_")) continue
                            val toolInput = value.optString("input", "")
                            // Merge tool result output (iOS: block.content = tr.output)
                            val result = toolResultMap[toolId]
                            val pageURL = value.optString("pageURL", "").ifEmpty { null }
                            val imgPath = value.optString("imageFilePath", "").ifEmpty { null }
                            blocks.add(AssistantBlock(
                                id = toolId,
                                kind = "tool_use",
                                toolName = value.optString("name", ""),
                                toolTitle = value.optString("description", ""),
                                toolArgs = toolInput,
                                content = result?.output?.lines()?.takeLast(80)?.joinToString("\n") ?: "",
                                toolStatus = when {
                                    result == null -> ToolBlockStatus.SUCCESS
                                    !result.success && (
                                        result.output.startsWith(ChatViewModel.CANCELLED_MARKER) ||
                                            result.output.startsWith(LEGACY_CANCELLED_MARKER)
                                    ) -> ToolBlockStatus.CANCELLED
                                    result.success -> ToolBlockStatus.SUCCESS
                                    else -> ToolBlockStatus.FAILED
                                },
                                browserURL = pageURL,
                                imageFilePath = imgPath,
                                // [T-android-gemini3-thoughtsig / #179] Restore the
                                // persisted signature onto the rebuilt block.
                                thoughtSignature = value.optString("thoughtSignature", "").ifEmpty { null },
                            ))
                        }
                        "mediaRef" -> {
                            val value = obj.optJSONObject("value") ?: continue
                            if (entity.role == "assistant") {
                                blocks.add(AssistantTurnCodec.restoreMediaBlock(value, mediaBaseDir))
                                continue
                            }
                            if (entity.role != "user") continue
                            val rel = value.optString("relativePath", "")
                            if (rel.isEmpty()) continue
                            val file = java.io.File(mediaBaseDir, rel)
                            if (!file.exists()) continue
                            val mime = value.optString("mimeType", "")
                            val name = value.optString("originalFileName", "").ifEmpty { file.name }
                            // T150: branch on mime so non-image mediaRefs land
                            // in the file-chip column instead of polluting
                            // imageUris (which feeds the image gallery).
                            if (mime.startsWith("image/")) {
                                restoredImageUris.add(Uri.fromFile(file))
                            } else {
                                restoredAttachmentUris.add(Uri.fromFile(file))
                            }
                            restoredAttachmentNames.add(name)
                        }
                        // toolResult in user messages handled in first pass above
                    }
                }
            } catch (e: Exception) {
                // T-PARTS-FALLBACK: previously this catch dumped the entire
                // partsJson into `text` as a degraded fallback. That meant
                // any malformed (or unexpectedly large) row rendered its
                // raw JSON — including any inlined base64 — as a plain
                // user/assistant bubble, which then locked up Compose's
                // StaticLayout for tens of seconds (see HangDetector report
                // for session e84882d7 / 820 KB partsJson). Replace with a
                // short, fixed-size placeholder so the row still appears
                // (so the user can delete or scroll past it) but no longer
                // pulls megabytes through the layout pass.
                Log.w(
                    TAG,
                    "toChatMessages: failed to parse partsJson for id=${entity.id} " +
                        "len=${entity.partsJson.length} role=${entity.role}: ${e.javaClass.simpleName}: ${e.message}",
                )
                text = "(message could not be parsed: ${e.javaClass.simpleName}, " +
                    "${entity.partsJson.length} bytes)"
            }

            // Skip user messages with no visible content (toolResult-only internal messages,
            // or messages that were entirely a system-reminder). A user message that is
            // *only* an image attachment (no caption) still has visible content and must
            // not be skipped — restoredImageUris carries it.
            if (entity.role == "user" && text.isBlank() && restoredImageUris.isEmpty()) return@mapNotNull null
            // Skip assistant messages that became empty after stripping system-reminders
            // and have no tool / thinking blocks to fall back on — would otherwise
            // render as a phantom blank assistant bubble.
            if (entity.role == "assistant" && text.isBlank() && blocks.isEmpty()) return@mapNotNull null
            ChatMessage(
                id = entity.id,
                role = entity.role,
                content = text,
                imageUris = restoredImageUris,
                attachmentNames = restoredAttachmentNames,
                attachmentUris = restoredAttachmentUris,
                toolBlocks = blocks,
                sourceDbIds = listOf(entity.id),
                createdAtMs = entity.createdAt,
                // [T-android-turn-work] updated_at is a nullable column and a row that was written
                // once never gets one; its created_at is then the best (and correct) end marker,
                // because the answer row is created when the turn ends.
                updatedAtMs = entity.updatedAt ?: entity.createdAt,
                // [T-error-persist-android] Restore the persisted terminal error
                // so the inline error banner + Retry button survive a reload.
                // Coalesce a blank value to null: the UI gate is `error?.let`, so
                // a non-null "" would render an empty banner. Defends against any
                // legacy/other-writer "" row.
                error = entity.errorInfo?.takeIf { it.isNotBlank() },
            )
        }.let { messages ->
            // Merge consecutive assistant messages into one:
            // agent loop persists each turn separately, but UI should show them as a single message.
            val merged = mutableListOf<ChatMessage>()
            for (msg in messages) {
                val prev = merged.lastOrNull()
                if (msg.role == "assistant" && prev?.role == "assistant") {
                    // Merge: combine tool blocks, append text, keep the last id.
                    // Deduplicate by block.id — the agent loop may persist the same tool
                    // use in multiple consecutive turns (as it carries tool state across),
                    // and duplicated ids would crash LazyColumn's key uniqueness check.
                    // Keep the LAST occurrence so the most recent status (e.g. SUCCESS with
                    // output) wins over an earlier STREAMING placeholder.
                    val seen = mutableSetOf<String>()
                    val combinedBlocks = (prev.toolBlocks + msg.toolBlocks)
                        .asReversed()
                        .filter { seen.add(it.id) }
                        .asReversed()
                    val combinedText = when {
                        prev.content.isBlank() -> msg.content
                        msg.content.isBlank() -> prev.content
                        else -> prev.content + "\n\n" + msg.content
                    }
                    merged[merged.lastIndex] = prev.copy(
                        id = msg.id,
                        content = combinedText,
                        toolBlocks = combinedBlocks,
                        // [T-android-turn-work] The turn ends when its LAST row was written, so the
                        // merged message keeps the later of the two end markers (the first row's
                        // own clock is the start of the work, not its finish).
                        updatedAtMs = maxOf(prev.updatedAtMs ?: 0L, msg.updatedAtMs ?: 0L)
                            .takeIf { it > 0L },
                        // T126-marker: keep every source dbId so Phase 2.5
                        // can resolve markers that point at any of the
                        // pre-merge rows (lastCompactedMessageId is often
                        // an assistant row that gets folded into a later
                        // assistant turn).
                        sourceDbIds = prev.sourceDbIds + msg.sourceDbIds,
                        // [T-error-persist-android] The error sticker is written
                        // to the LAST assistant row of the turn, so the later row
                        // (`msg`) wins; fall back to `prev` if only it carried one.
                        error = msg.error ?: prev.error,
                    )
                } else {
                    merged.add(msg)
                }
            }
            merged
        }
    }

    private data class ToolResultData(val output: String, val success: Boolean)

    private fun MessageEntity.mapToLlm(parsedParts: Result<org.json.JSONArray>? = null): LLMMessage {
        val r = if (role == "user") LLMMessage.Role.USER else LLMMessage.Role.ASSISTANT
        val contentParts = mutableListOf<AgentContentPart>()
        val imageParts = mutableListOf<LLMMessage.ImagePart>()
        var textContent = ""

        try {
            val array = parsedParts?.getOrThrow() ?: org.json.JSONArray(partsJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                when (obj.optString("type")) {
                    "text" -> {
                        val value = obj.optString("value", "")
                        // [T-android-retry-attachment-loss] The persisted
                        // <user-attached-files> XML must reach the model via a
                        // contentPart (provider prefers contentParts), but it
                        // must NOT fold into `content`. On a FRESH send the
                        // `content` field is the clean caption (`trimmed`) and
                        // the XML lives only in contentParts; keep restored
                        // messages byte-identical so `.content` consumers
                        // (summary, title fallback, edit) see the same string
                        // as a fresh turn and don't get the XML twice.
                        if (value.contains("<user-attached-files>")) {
                            contentParts.add(AgentContentPart.Text(value))
                        } else {
                            textContent += value
                            contentParts.add(AgentContentPart.Text(value))
                        }
                    }
                    "toolUse" -> {
                        val v = obj.getJSONObject("value")
                        val inputStr = v.optString("input", "{}")
                        val inputJson = try {
                            JSONObject(inputStr)
                        } catch (_: Exception) {
                            JSONObject()
                        }
                        contentParts.add(AgentContentPart.ToolUse(
                            id = v.optString("toolUseId", ""),
                            name = v.optString("name", ""),
                            input = inputJson,
                            // [T-android-gemini3-thoughtsig / #179] Restore the
                            // persisted Gemini 3.x signature so a reloaded session
                            // replays it (else the next gemini-3 turn 400s).
                            thoughtSignature = v.optString("thoughtSignature", "").ifEmpty { null },
                        ))
                    }
                    "toolResult" -> {
                        val v = obj.getJSONObject("value")
                        contentParts.add(AgentContentPart.ToolResult(
                            id = v.optString("toolUseId", ""),
                            name = v.optString("name", ""),
                            content = v.optString("output", ""),
                            isError = !v.optBoolean("success", true),
                        ))
                    }
                    "mediaRef" -> {
                        // T128: load persisted user-message images so the model
                        // sees them on subsequent turns after a session reload.
                        // T150: skip non-image mediaRefs here — their bytes
                        // shouldn't be re-inlined into the LLM payload (parity
                        // with the on-send path, which only inlines images).
                        // The original turn's <user-attached-files> XML stayed
                        // in the persisted text part, and the file is still
                        // on disk under attachments/uploads, so the agent can
                        // re-fetch via shell tools.
                        val v = obj.optJSONObject("value") ?: continue
                        val rel = v.optString("relativePath", "")
                        if (rel.isEmpty()) continue
                        val mime = v.optString("mimeType", "image/jpeg")
                        if (!mime.startsWith("image/")) continue
                        val file = java.io.File(mediaBaseDir, rel)
                        if (!file.exists()) continue
                        val bytes = try { file.readBytes() } catch (_: Exception) { continue }
                        val restoredPath = v.optString("linuxPath", "").ifEmpty { null }
                        // [T-android-vision-group / GH#182] Seed the read_image
                        // hint on restored images too, so a non-vision main model
                        // with a Vision Group configured gets steered to read_image
                        // on subsequent turns after a session reload (not the bare
                        // "can't see it" literal).
                        val restoredPlaceholder = visionPlaceholderFor(restoredPath)
                        imageParts.add(LLMMessage.ImagePart(bytes, mime, linuxPath = restoredPath, noVisionPlaceholder = restoredPlaceholder))
                        contentParts.add(AgentContentPart.ImageData(bytes, mime, linuxPath = restoredPath, noVisionPlaceholder = restoredPlaceholder))
                    }
                }
            }
        } catch (_: Exception) {
            textContent = partsJson
            contentParts.add(AgentContentPart.Text(partsJson))
        }

        return LLMMessage(
            role = r,
            content = textContent,
            imageParts = imageParts,
            contentParts = contentParts,
            dbMessageId = id,
            reasoningContent = reasoningContent,
        )
    }
}
