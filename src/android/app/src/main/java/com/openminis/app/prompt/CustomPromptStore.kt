package com.openminis.app.prompt

import android.content.Context
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * [T-system-prompt-custom] The device owner's own system prompt.
 *
 * This is the single free-text box in Settings -> System prompt: whatever the
 * user writes there is injected ahead of every other authored section of the
 * agent prompt and is declared to outrank them (personality, response style,
 * presets, bot instructions, session overlays). Codex-style custom
 * instructions, not a per-section editor.
 *
 * Stored as a plain file next to the module overrides so it survives app
 * updates, can be inspected/backed up, and is never rewritten by the module
 * "reset all" path. Empty text means "inject nothing" — the assembled prompt
 * then stays byte-identical to a build without this feature.
 *
 * [text] is cached because the prompt is composed synchronously on the send
 * path; [warm] fills the cache at app start and every write refreshes it.
 */
object CustomPromptStore {

    private const val TAG = "CustomPromptStore"
    const val FILE_NAME = "custom.md"

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cache: String? = null

    private val _textFlow = MutableStateFlow("")
    /** Live view of the saved text, used by the Settings editor. */
    val textFlow: StateFlow<String> = _textFlow.asStateFlow()

    /** Saved text, or null when the box is empty. Cached for the send path. */
    fun text(context: Context): String? {
        cache?.let { return it.ifEmpty { null } }
        return synchronized(lock) {
            if (cache == null) cache = read(context)
            cache?.ifEmpty { null }
        }
    }

    /** Re-read from disk, replacing the cache and publishing to [textFlow]. */
    fun refresh(context: Context): String = synchronized(lock) {
        val value = read(context)
        cache = value
        _textFlow.value = value
        value
    }

    /** Pre-load the cache off the main thread. Safe to call on every launch. */
    fun warm(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            try {
                refresh(appContext)
            } catch (t: Throwable) {
                AppLogger.warning(TAG, "warm failed: ${t.message}")
            }
        }
    }

    /** Persist the box content. Blank text clears the file. */
    fun save(context: Context, text: String) {
        val normalized = normalize(text)
        val file = file(context)
        try {
            if (normalized.isEmpty()) {
                if (file.exists() && !file.delete()) {
                    AppLogger.warning(TAG, "could not delete ${FILE_NAME}")
                }
            } else {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, "$FILE_NAME.tmp")
                tmp.writeText(normalized, Charsets.UTF_8)
                if (!tmp.renameTo(file)) {
                    file.writeText(normalized, Charsets.UTF_8)
                    tmp.delete()
                }
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "save failed: ${t.message}")
        }
        synchronized(lock) {
            cache = normalized
            _textFlow.value = normalized
        }
    }

    /** Clear the box (same as saving blank text). */
    fun clear(context: Context) = save(context, "")

    private fun file(context: Context) =
        File(File(context.filesDir, PromptModuleStore.OVERRIDE_DIR_NAME), FILE_NAME)

    private fun read(context: Context): String {
        val file = file(context)
        if (!file.isFile) return ""
        return try {
            normalize(file.readText(Charsets.UTF_8))
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "read failed: ${t.message}")
            ""
        }
    }

    /** Surrounding whitespace is dropped; the composer owns the layout around it. */
    private fun normalize(text: String): String = text.trim()
}
