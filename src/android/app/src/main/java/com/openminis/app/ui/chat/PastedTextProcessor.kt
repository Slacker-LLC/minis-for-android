package com.openminis.app.ui.chat

import android.net.Uri
import com.openminis.app.data.model.MediaRef
import com.openminis.app.data.storage.MediaStore
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Result of resolving and persisting pasted text placeholders.
 */
data class PastedParts(
    val partsJson: List<String>,
    val modelText: String,
    val uiNames: List<String>,
    val uiUris: List<Uri>,
    val consumedIds: Set<Int>,
    val createdFiles: List<File> = emptyList(),
)

/**
 * Helper object for processing pasted texts off the main thread and cleaning up
 * written disk artifacts if message persistence fails.
 */
object PastedTextProcessor {
    private const val TAG = "PastedTextProcessor"

    internal fun escapeJson(value: String): String = JSONObject.quote(value)

    internal fun buildMediaRefPartJson(ref: MediaRef): String {
        val value = JSONObject()
            .put("id", ref.id)
            .put("relativePath", ref.relativePath)
            .put("mimeType", ref.mimeType)
        if (ref.originalFileName != null) {
            value.put("originalFileName", ref.originalFileName)
        }
        return JSONObject().put("type", "mediaRef").put("value", value).toString()
    }

    /**
     * Parse and persist pasted text chunks to media storage on [Dispatchers.IO].
     */
    suspend fun processPastedParts(
        text: String,
        pastedTexts: List<PastedText>,
        sessionId: String,
        mediaStore: MediaStore,
    ): PastedParts? = withContext(Dispatchers.IO) {
        val (chunks, consumed) = splitPastePlaceholders(text, pastedTexts)
        if (consumed.isEmpty()) return@withContext null
        val byId = pastedTexts.associateBy { it.id }

        val parts = mutableListOf<String>()
        val model = StringBuilder()
        val names = mutableListOf<String>()
        val uris = mutableListOf<Uri>()
        val createdFiles = mutableListOf<File>()

        for (chunk in chunks) {
            when (chunk) {
                is PasteChunk.Text -> {
                    parts.add("""{"type":"text","value":${escapeJson(chunk.value)}}""")
                    model.append(chunk.value)
                }
                is PasteChunk.Pasted -> {
                    val entry = byId[chunk.id] ?: continue
                    val ref = try {
                        mediaStore.saveMedia(
                            data = entry.text.toByteArray(Charsets.UTF_8),
                            mimeType = PastedMedia.MIME,
                            sessionId = sessionId,
                            originalFileName = PastedMedia.fileNameFor(chunk.id),
                        )
                    } catch (e: Exception) {
                        AppLogger.warning(
                            TAG,
                            "[Paste] saveMedia failed for #${chunk.id}, inlining: ${e.message}",
                        )
                        parts.add("""{"type":"text","value":${escapeJson(entry.text)}}""")
                        model.append(entry.text)
                        continue
                    }
                    val file = File(mediaStore.mediaBaseDir, ref.relativePath)
                    createdFiles.add(file)
                    parts.add(buildMediaRefPartJson(ref))
                    model.append(entry.text)
                    names.add(ref.originalFileName ?: PastedMedia.fileNameFor(chunk.id))
                    uris.add(Uri.fromFile(file))
                }
            }
        }
        AppLogger.info(
            TAG,
            "[Paste] ${consumed.size} placeholder(s) -> mediaRef: ${text.length} chars in bubble, ${model.length} chars to model",
        )
        PastedParts(
            partsJson = parts,
            modelText = model.toString(),
            uiNames = names,
            uiUris = uris,
            consumedIds = consumed,
            createdFiles = createdFiles,
        )
    }

    /**
     * Delete files created during [processPastedParts] if message persistence subsequently fails.
     */
    fun cleanupFiles(pastedParts: PastedParts?) {
        if (pastedParts == null) return
        for (file in pastedParts.createdFiles) {
            try {
                if (file.exists()) {
                    file.delete()
                }
            } catch (e: Exception) {
                AppLogger.warning(TAG, "Failed to cleanup file ${file.absolutePath}: ${e.message}")
            }
        }
    }
}
