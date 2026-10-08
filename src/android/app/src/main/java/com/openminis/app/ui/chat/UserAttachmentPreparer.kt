package com.openminis.app.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.storage.MediaStore
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ImageBudget
import java.io.ByteArrayOutputStream
import org.json.JSONObject

/**
 * Turns the attachments of one outgoing user message into what the rest of the send needs: resized
 * image bytes for the model, persisted copies and `mediaRef` parts so they survive a reload, and the
 * copies in the session's uploads folder the agent's shell tools can open. Moved out of
 * [ChatViewModel] unchanged; [onImageBudget] is told when the byte budget touched the images.
 */
internal class UserAttachmentPreparer(
    private val context: Context,
    private val mediaStore: MediaStore,
    private val onImageBudget: (ImageBudget.BudgetResult) -> Unit,
) {
    companion object {
        private const val TAG = "ChatViewModel"

        /**
         * Compute a unique-on-disk filename inside [dir] for [original]. Strips
         * path separators, falls back to "image.jpg" if the input is empty, and
         * appends `_N` before the extension when the target already exists.
         */
        internal fun uniqueUploadFileName(dir: java.io.File, original: String): String {
            val raw = original.substringAfterLast('/').substringAfterLast('\\').ifBlank { "image.jpg" }
            // Sanitize control / path-hostile chars without going overboard;
            // safe POSIX path chars are kept.
            val sanitized = raw.replace(Regex("[^A-Za-z0-9._-]"), "_")
            if (!java.io.File(dir, sanitized).exists()) return sanitized
            val dot = sanitized.lastIndexOf('.')
            val base = if (dot > 0) sanitized.substring(0, dot) else sanitized
            val ext = if (dot > 0) sanitized.substring(dot) else ""
            var n = 1
            while (true) {
                val candidate = "${base}_$n$ext"
                if (!java.io.File(dir, candidate).exists()) return candidate
                n++
            }
        }

        internal fun buildMediaRefPartJson(
            ref: com.openminis.app.data.model.MediaRef,
            linuxPath: String? = null,
        ): String {
            val value = JSONObject()
                .put("id", ref.id)
                .put("relativePath", ref.relativePath)
                .put("mimeType", ref.mimeType)
            if (ref.originalFileName != null) value.put("originalFileName", ref.originalFileName)
            // Carry the iSH-visible uploads path through persistence so that
            // restored history can reconstruct AgentContentPart.ImageData with
            // its original linuxPath. Restored images that miss this field
            // (older rows written before this column existed) get linuxPath=null
            // and fall back to spillover at budget-elide time.
            if (linuxPath != null) value.put("linuxPath", linuxPath)
            return JSONObject().put("type", "mediaRef").put("value", value).toString()
        }
    }

    /**
     * T209: resize image bytes for the LLM inference payload only — the
     * full-resolution original is preserved on disk (mediaStore + uploads
     * dir) so chat history fullscreen view, agent shell `cat`, and
     * `read_image` all see the user's original picture, matching iOS.
     *
     * Returns null when the source already fits within [maxEdge] (caller
     * should fall back to [rawBytes]) or on any decode/compress failure.
     */
    private fun resizeImageBytes(
        rawBytes: ByteArray,
        mimeType: String,
        maxEdge: Int = 2000,
    ): ByteArray? {
        return try {
            val original = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return null
            if (original.width <= maxEdge && original.height <= maxEdge) {
                original.recycle()
                return null
            }
            val scale = maxEdge.toFloat() / maxOf(original.width, original.height)
            val w = (original.width * scale).toInt()
            val h = (original.height * scale).toInt()
            val scaled = Bitmap.createScaledBitmap(original, w, h, true)
            val out = ByteArrayOutputStream()
            val format = if (mimeType.contains("png")) Bitmap.CompressFormat.PNG
            else Bitmap.CompressFormat.JPEG
            scaled.compress(format, 85, out)
            if (scaled !== original) scaled.recycle()
            original.recycle()
            out.toByteArray()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Bundle of everything derived from a user-message's input attachments:
     * the resized in-memory image bytes for the LLM, file:// URIs of the
     * persisted copies (for stable rendering across app restarts), the
     * filenames in original attachment order (images first, then non-image
     * files — matches the rendering convention in UserAttachmentList), and
     * the mediaRef JSON parts that need to be embedded in parts_json so the
     * attachments survive a session reload (T128).
     */
    data class PreparedAttachments(
        val imageParts: List<LLMMessage.ImagePart>,
        val imageUris: List<Uri>,
        val imageRefs: List<com.openminis.app.data.model.MediaRef>,
        val attachmentNames: List<String>,
        val mediaRefPartsJson: List<String>,
        // T132: iOS-parity additions so the model sees the attachment as
        // a real file in the agent's sandbox (read_image / shell_execute can
        // open these paths).
        //   imageUploadPaths: one /var/minis/attachments/uploads/<safe> per
        //     inlined image, in the same order as `imageParts`.
        //   attachedFilesXml:  null when no attachments, otherwise the
        //     <user-attached-files> XML block iOS appends to the user turn.
        val imageUploadPaths: List<String>,
        val attachedFilesXml: String?,
        // T150: file:// URIs of persisted non-image attachments, in the same
        // order as the non-image suffix of `attachmentNames`. Carried into
        // ChatMessage so the user-bubble file chip can route a tap directly
        // to FilePreviewScreen without re-resolving by filename.
        val nonImageUris: List<Uri>,
    )

    /**
     * Resize each image attachment, copy the bytes into MediaStore (private
     * filesDir/media/<date>/<sessionId>/<id>.<ext>), and return both the
     * in-memory bytes (for the LLM) and a stable file:// URI + mediaRef JSON
     * part (for persistence + reload). T150: non-image attachments take the
     * same persistence + uploadsHostDir path so they survive session reload
     * and remain visible to the agent's shell tools — but their content is
     * NOT inlined into the LLM payload (parity with iOS processAttachments,
     * AIChatViewModel.swift L1552-1645).
     */
    fun prepare(
        attachments: List<InputAttachment>,
        sessionId: String,
    ): PreparedAttachments {
        val imageParts = mutableListOf<LLMMessage.ImagePart>()
        val imageUris = mutableListOf<Uri>()
        val imageMediaRefs = mutableListOf<com.openminis.app.data.model.MediaRef>()
        val imageNames = mutableListOf<String>()
        val nonImageNames = mutableListOf<String>()
        val nonImageUris = mutableListOf<Uri>()
        // T150: separate buffers so the persisted mediaRefPartsJson is
        // image-first, matching the on-screen UserAttachmentList ordering
        // and `attachmentNames = imageNames + nonImageNames`. On restore,
        // `loadSessionMessages` walks parts_json in array order — keeping
        // the persisted order image-first means restoredAttachmentNames
        // and restoredAttachmentUris also come out image-first/non-image-suffix.
        val imageMediaRefPartsJson = mutableListOf<String>()
        val nonImageMediaRefPartsJson = mutableListOf<String>()
        val imageUploadPaths = mutableListOf<String>()
        // T132: also write the resized bytes into the session's iSH-bound
        // attachments dir (filesDir/minis-sessions/<sid>/attachments/uploads/),
        // which is mounted at /var/minis/attachments/ inside iSH. This makes
        // the same image accessible to the agent via shell tools (read_image
        // / cat / file) and matches the iOS uploads-directory convention.
        val uploadsHostDir = java.io.File(
            com.openminis.app.runtime.ubuntu.UbuntuPaths.ensureSessionDirs(sessionId)
                ?: java.io.File(com.openminis.app.runtime.ubuntu.UbuntuPaths.hostSessions, sessionId),
            "attachments/uploads",
        ).apply { mkdirs() }
        // Metadata captured per attachment for the <user-attached-files> XML.
        data class UploadMeta(val linuxPath: String, val size: Long, val modifiedIso: String, val body: InlineText?)
        val metas = mutableListOf<UploadMeta>()
        var inlineBudget = INLINE_TEXT_TOTAL_CHARS
        val nowMs = System.currentTimeMillis()
        val isoFormatter = java.text.SimpleDateFormat(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            java.util.Locale.US,
        ).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        val nowStr = isoFormatter.format(java.util.Date(nowMs))

        for (attachment in attachments) {
            if (attachment.isImage) {
                // T209: read the original image bytes once and reuse them
                // for storage + uploads dir; only the LLM inference payload
                // gets the resized copy. Pre-T209 the resized JPEG was used
                // for all three, so chat history fullscreen view and agent
                // shell tools (read_image / cat) saw a 1024px JPEG instead
                // of the user's original picture. Matches iOS canonical
                // (AIChatViewModel.swift L1595-1617).
                val rawBytes = try {
                    context.contentResolver.openInputStream(attachment.uri)?.use { it.readBytes() }
                } catch (e: Exception) {
                    Log.w(TAG, "image read failed for ${attachment.fileName}: ${e.message}")
                    null
                } ?: continue
                val ref = try {
                    mediaStore.saveMedia(
                        data = rawBytes,
                        mimeType = attachment.mimeType,
                        sessionId = sessionId,
                        originalFileName = attachment.fileName,
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to persist image attachment ${attachment.fileName}", e)
                    continue
                }
                imageMediaRefs.add(ref)
                // Resize only for the LLM payload — token-efficient and a
                // close-enough sketch of the picture for the model. Falls
                // back to raw bytes if the source is already small or the
                // decode/compress step fails.
                val inferenceBytes = resizeImageBytes(rawBytes, attachment.mimeType, maxEdge = 2000)
                    ?: rawBytes

                // Mirror ORIGINAL bytes into the iSH uploads dir under a
                // unique safe name so agent shell tools see the full-res
                // image. Don't fail the send if this write fails —
                // image_url in the request still carries (resized) bytes;
                // the model just won't be able to ask the agent to re-read
                // the same file from shell.
                //
                // Done BEFORE ImagePart construction so the linuxPath is
                // attached to the part — request-level image budgeting
                // uses it to emit a re-fetchable text placeholder when
                // the cumulative payload would exceed the per-request cap.
                val safeName = uniqueUploadFileName(uploadsHostDir, attachment.fileName)
                val dest = java.io.File(uploadsHostDir, safeName)
                val uploadOk = try { dest.writeBytes(rawBytes); true } catch (e: Exception) {
                    Log.w(TAG, "uploads write failed for ${attachment.fileName}: ${e.message}")
                    false
                }
                val linuxPath = if (uploadOk) "/var/minis/attachments/uploads/$safeName" else null
                if (linuxPath != null) {
                    imageUploadPaths.add(linuxPath)
                    metas.add(UploadMeta(linuxPath = linuxPath, size = rawBytes.size.toLong(), modifiedIso = nowStr, body = null))
                }

                imageParts.add(LLMMessage.ImagePart(inferenceBytes, attachment.mimeType, linuxPath = linuxPath))
                val savedFile = java.io.File(mediaStore.mediaBaseDir, ref.relativePath)
                imageUris.add(Uri.fromFile(savedFile))
                imageNames.add(attachment.fileName)
                imageMediaRefPartsJson.add(buildMediaRefPartJson(ref, linuxPath = linuxPath))
                continue
            }

            // T150: non-image attachment — stream-copy to disk (no
            // resize), persist a mediaRef so the chip survives session
            // reload (T151), and put a copy in the iSH uploads dir so
            // the agent can `cat` it via shell tools. iOS parity: the
            // file content is NOT inlined into the LLM payload — it
            // only appears in <user-attached-files> XML metadata, the
            // model fetches content on demand.
            //
            // CRITICAL: we deliberately do NOT `readBytes()` the
            // attachment here. A 400MB APK shared in by the user would
            // OOM on a low-RAM device (heap growth limit ~500MB on
            // Pixel 4a); the file's not even going into the LLM
            // payload, so loading the full byte array is pointless.
            // Stream-copy to the uploads dest first, then hand that
            // file to MediaStore.saveMediaStreamed so a second
            // streaming pass produces the durable mediaRef.
            nonImageNames.add(attachment.fileName)
            val safeName = uniqueUploadFileName(uploadsHostDir, attachment.fileName)
            val dest = java.io.File(uploadsHostDir, safeName)
            val uploadOk = try {
                context.contentResolver.openInputStream(attachment.uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                } != null
            } catch (e: Exception) {
                Log.w(TAG, "non-image upload write failed for ${attachment.fileName}: ${e.message}")
                runCatching { dest.delete() }
                false
            }
            if (!uploadOk) continue

            val ref = try {
                dest.inputStream().use { input ->
                    mediaStore.saveMediaStreamed(
                        source = input,
                        mimeType = attachment.mimeType,
                        sessionId = sessionId,
                        originalFileName = attachment.fileName,
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to persist non-image attachment ${attachment.fileName}", e)
                null
            }
            if (ref != null) {
                nonImageMediaRefPartsJson.add(buildMediaRefPartJson(ref))
                nonImageUris.add(Uri.fromFile(java.io.File(mediaStore.mediaBaseDir, ref.relativePath)))
            }

            val linuxPath = "/var/minis/attachments/uploads/$safeName"
            // A text file's head goes to the model with the message; a binary or oversize one stays
            // metadata-only, and the model reads it from linuxPath when it needs more.
            val body = if (inlineBudget > 0 && isTextLikeAttachment(attachment.fileName, attachment.mimeType)) {
                readInlineText(dest, minOf(INLINE_TEXT_PER_FILE_CHARS, inlineBudget))?.also { inlineBudget -= it.text.length }
            } else {
                null
            }
            metas.add(UploadMeta(linuxPath = linuxPath, size = dest.length(), modifiedIso = nowStr, body = body))
        }

        // T-imgsize: byte-level budget enforcement. The resizeImageBytes pass
        // above caps *resolution* at 2000px but does nothing for the JPEG byte
        // size when the source is a 12-megapixel photo — Anthropic 413s once
        // cumulative inline image payload crosses ~30MB. ImageBudget walks
        // every image part, re-encodes oversize ones via the quality ladder,
        // and drops the tail when cumulative bytes would exceed 20MB. Result
        // is surfaced to the UI through _imageBudgetEvent so the Snackbar can
        // tell the user we touched their attachments.
        if (imageParts.isNotEmpty()) {
            val budgetResult = ImageBudget.applyMessageBudget(imageParts.map { it.data })
            // budgetResult.keptBytes.size <= imageParts.size; tail-drop the
            // parallel image-only lists symmetrically. Re-encoded bytes always
            // come out as JPEG so flip the mimeType on any part whose bytes
            // changed size (cheap proxy — never a false positive that hurts
            // semantics because the byte stream itself is the JPEG header).
            val newImageParts = budgetResult.keptBytes.mapIndexed { idx, kept ->
                val orig = imageParts[idx]
                if (kept === orig.data) orig
                else LLMMessage.ImagePart(kept, "image/jpeg", linuxPath = orig.linuxPath)
            }
            val newSize = newImageParts.size
            imageParts.clear()
            imageParts.addAll(newImageParts)
            while (imageUris.size > newSize) imageUris.removeAt(imageUris.size - 1)
            while (imageNames.size > newSize) imageNames.removeAt(imageNames.size - 1)
            while (imageMediaRefPartsJson.size > newSize) imageMediaRefPartsJson.removeAt(imageMediaRefPartsJson.size - 1)
            while (imageUploadPaths.size > newSize) imageUploadPaths.removeAt(imageUploadPaths.size - 1)
            if (budgetResult.mutated) {
                AppLogger.info(
                    TAG,
                    "[ImageBudget] compose: in=${budgetResult.keptBytes.size + budgetResult.droppedCount} kept=${budgetResult.keptBytes.size} compressed=${budgetResult.compressedCount} dropped=${budgetResult.droppedCount} totalBytes=${budgetResult.totalBytes}",
                )
                onImageBudget(budgetResult)
            }
        }

        // Build the <user-attached-files> XML block (iOS parity). One <file>
        // per attachment that landed in the iSH uploads dir. Text files carry
        // their head inside the element; everything else is an inventory
        // entry the model can resolve via shell tools.
        val xml = if (metas.isEmpty()) null else buildString {
            append("<user-attached-files>\n")
            for (m in metas) {
                val urlPath = m.linuxPath.removePrefix("/var/minis/")
                append("  <file path=\"")
                append(m.linuxPath)
                append("\" url=\"minis://")
                append(urlPath)
                append("\" size=\"")
                append(m.size)
                append("\" modified=\"")
                append(m.modifiedIso)
                val body = m.body
                if (body == null) {
                    append("\" />\n")
                } else {
                    append("\">\n")
                    if (body.truncated) {
                        append("[truncated: the first ${body.text.length} characters are shown; the file is ${body.totalBytes} bytes. Read the rest from ${m.linuxPath} with a tool.]\n")
                    }
                    append(escapeInlineBody(body.text))
                    append("\n  </file>\n")
                }
            }
            append("</user-attached-files>")
        }

        // Order matches UserAttachmentList convention: images first, then files.
        return PreparedAttachments(
            imageParts = imageParts,
            imageUris = imageUris,
            imageRefs = imageMediaRefs.toList(),
            attachmentNames = imageNames + nonImageNames,
            mediaRefPartsJson = imageMediaRefPartsJson + nonImageMediaRefPartsJson,
            imageUploadPaths = imageUploadPaths,
            attachedFilesXml = xml,
            nonImageUris = nonImageUris,
        )
    }
}
