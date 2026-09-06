package com.openminis.app.ui.chat

import android.net.Uri
import com.openminis.app.data.model.MediaRef
import com.openminis.app.data.storage.MediaStore
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
    val consumedIds: Set<Int>,
    val createdFiles: List<File> = emptyList(),
) {
    // Android presentation is derived after preparation/commit, from the same
    // owned files used by rollback; no second URI list can drift out of sync.
    val uiUris: List<Uri> get() = createdFiles.map { Uri.fromFile(it) }
}

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
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    ): PastedParts? {
        // Ownership must live outside withContext: cancellation can discard a
        // successful IO result while dispatching it back to the caller.
        val createdFiles = mutableListOf<File>()
        try {
            return withContext(ioDispatcher) {
                val (chunks, consumed) = splitPastePlaceholders(text, pastedTexts)
                if (consumed.isEmpty()) return@withContext null
                val byId = pastedTexts.associateBy { it.id }

                val parts = mutableListOf<String>()
                val model = StringBuilder()
                val names = mutableListOf<String>()

                for (chunk in chunks) {
                    currentCoroutineContext().ensureActive()
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
                                if (e is CancellationException) throw e
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
                    consumedIds = consumed,
                    createdFiles = createdFiles,
                )
            }
        } catch (error: Throwable) {
            withContext(NonCancellable + ioDispatcher) { cleanupFiles(createdFiles) }
            throw error
        }
    }

    /** The short DB commit and buffer consumption share one cancellation boundary. */
    suspend fun <T> commitMessage(
        pastedParts: PastedParts?,
        persist: suspend () -> T,
        consume: (Set<Int>) -> Unit,
    ): T {
        var committed = false
        try {
            currentCoroutineContext().ensureActive()
            return withContext(NonCancellable) {
                val value = persist()
                committed = true
                pastedParts?.let { consume(it.consumedIds) }
                value
            }
        } catch (error: Throwable) {
            if (!committed) withContext(NonCancellable + Dispatchers.IO) { cleanupFiles(pastedParts) }
            throw error
        }
    }

    /**
     * Delete files created during [processPastedParts] if message persistence subsequently fails.
     */
    fun cleanupFiles(pastedParts: PastedParts?) {
        if (pastedParts == null) return
        cleanupFiles(pastedParts.createdFiles)
    }

    private fun cleanupFiles(files: List<File>) {
        for (file in files) {
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
