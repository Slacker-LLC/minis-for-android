package com.openminis.app.prompt

import android.content.Context
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * [T-system-prompt-modules] Storage for the editable system prompt modules.
 *
 * Two layers, deliberately separate:
 *
 *  - **Shipped defaults** — `assets/prompts/<id>.md`, read-only, replaced on app
 *    update. This is also the reset target: an override is deleted, not rewritten.
 *  - **User overrides** — `<filesDir>/system_prompt/<id>.md`, written by the
 *    Settings editor and by `minis-config`. An absent file means "use the
 *    shipped default".
 *
 * `enabled.<id>` lives in SharedPreferences `minis_system_prompt` so a section
 * can be silenced without destroying its text (and so the AI, which cannot edit
 * the asset tree, can still switch one off through the config bridge).
 *
 * The prompt is composed synchronously on the send path, so [snapshots] keeps the
 * resolved state in memory: [warm] pre-loads it off the main thread at app start,
 * and every write path refreshes it.
 */
object PromptModuleStore {

    private const val TAG = "PromptModuleStore"
    private const val PREFS_NAME = "minis_system_prompt"
    /** Directory (under `filesDir`) holding user overrides: module files and custom.md. */
    internal const val OVERRIDE_DIR_NAME = "system_prompt"
    private const val ENABLED_PREFIX = "enabled."

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var cache: List<ModuleSnapshot>? = null

    /**
     * Live view of the resolved modules, published by every read/write path that
     * goes through [snapshots] or [refresh]. The Settings editor collects this so
     * a write from another surface — `minis-config` through the config bridge is
     * the important one — shows up without leaving and re-entering the screen.
     * Empty until the first load; [warm] usually fills it at app start.
     */
    private val _snapshotsFlow = MutableStateFlow<List<ModuleSnapshot>>(emptyList())
    val snapshotsFlow: StateFlow<List<ModuleSnapshot>> = _snapshotsFlow.asStateFlow()

    /** Where shipped default texts come from. Swapped in tests for a file tree. */
    interface DefaultsSource {
        fun read(assetName: String): String?
    }

    /** Production source: the APK's `assets/prompts/` directory. */
    class AssetDefaults(private val context: Context) : DefaultsSource {
        override fun read(assetName: String): String? = try {
            context.assets
                .open("${PromptModuleRegistry.ASSET_DIR}/$assetName")
                .use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "default asset unavailable: $assetName (${t.message})")
            null
        }
    }

    /** Test source: a plain directory (e.g. `src/main/assets/prompts`). */
    class DirectoryDefaults(private val dir: File) : DefaultsSource {
        override fun read(assetName: String): String? {
            val file = File(dir, assetName)
            return if (file.isFile) file.readText(Charsets.UTF_8) else null
        }
    }

    /** Resolved state of one module: shipped default + optional override. */
    data class ModuleSnapshot(
        val module: PromptModule,
        val defaultText: String,
        val overrideText: String?,
        val isEnabled: Boolean,
    ) {
        /** Text used when the module is enabled: the override, else the default. */
        val text: String get() = overrideText ?: defaultText

        /** True when the user (or the AI) has replaced the shipped wording. */
        val isCustomized: Boolean get() = overrideText != null
    }

    /**
     * Resolved snapshots for every registered module, in assembly order.
     *
     * Cached after the first call (see [warm]); the cache is only ever replaced
     * by [refresh], so a caller on the prompt path never touches disk twice.
     */
    fun snapshots(context: Context): List<ModuleSnapshot> {
        cache?.let { return it }
        return synchronized(lock) {
            cache ?: load(context).also {
                cache = it
                _snapshotsFlow.value = it
            }
        }
    }

    /** Re-read defaults + overrides, replacing the cache. */
    fun refresh(context: Context): List<ModuleSnapshot> =
        synchronized(lock) {
            load(context).also {
                cache = it
                _snapshotsFlow.value = it
            }
        }

    /**
     * Resolve the current state without touching the cache. Used by tests and by
     * callers that need a guaranteed-fresh read (the Settings editor reloads
     * through this after its own writes).
     */
    fun load(context: Context, source: DefaultsSource = AssetDefaults(context)): List<ModuleSnapshot> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return PromptModuleRegistry.modules.map { module ->
            val default = source.read(module.assetName)
            if (default == null) {
                AppLogger.warning(TAG, "missing default prompt module: ${module.assetName}")
            }
            ModuleSnapshot(
                module = module,
                defaultText = normalize(default.orEmpty()),
                overrideText = readOverride(context, module),
                isEnabled = prefs.getBoolean(ENABLED_PREFIX + module.id, true),
            )
        }
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

    /**
     * Persist a user override. Text identical to the shipped default (or blank)
     * deletes the override file, so the module keeps tracking app updates.
     */
    fun saveOverride(context: Context, moduleId: String, text: String) {
        val module = PromptModuleRegistry.byId(moduleId) ?: return
        val file = overrideFile(context, module)
        val normalized = normalize(text)
        val default = normalize(AssetDefaults(context).read(module.assetName).orEmpty())
        if (normalized.isEmpty() || normalized == default) {
            if (file.exists() && !file.delete()) {
                AppLogger.warning(TAG, "could not delete override: ${file.name}")
            }
        } else {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(normalized, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(normalized, Charsets.UTF_8)
                tmp.delete()
            }
        }
        refresh(context)
    }

    /** Drop the override so the module falls back to the shipped default. */
    fun resetOverride(context: Context, moduleId: String) {
        val module = PromptModuleRegistry.byId(moduleId) ?: return
        val file = overrideFile(context, module)
        if (file.exists() && !file.delete()) {
            AppLogger.warning(TAG, "could not delete override: ${file.name}")
        }
        refresh(context)
    }

    /** Include or skip a module without touching its text. */
    fun setEnabled(context: Context, moduleId: String, enabled: Boolean) {
        if (PromptModuleRegistry.byId(moduleId) == null) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(ENABLED_PREFIX + moduleId, enabled)
            .apply()
        refresh(context)
    }

    /**
     * Restore every module: overrides deleted, all modules re-enabled.
     *
     * Deletes module override files one by one on purpose — the directory also
     * holds the device owner's custom system prompt (CustomPromptStore), which
     * this per-module reset must not touch.
     */
    fun resetAll(context: Context) {
        for (module in PromptModuleRegistry.modules) {
            val file = overrideFile(context, module)
            if (file.exists() && !file.delete()) {
                AppLogger.warning(TAG, "could not delete override: ${file.name}")
            }
        }
        val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
        for (module in PromptModuleRegistry.modules) {
            editor.remove(ENABLED_PREFIX + module.id)
        }
        editor.apply()
        refresh(context)
    }

    /** True when any module has an override or has been switched off. */
    fun hasAnyCustomization(context: Context): Boolean =
        snapshots(context).any { snapshot -> snapshot.isCustomized || !snapshot.isEnabled }

    private fun overrideDir(context: Context) = File(context.filesDir, OVERRIDE_DIR_NAME)

    private fun overrideFile(context: Context, module: PromptModule) =
        File(overrideDir(context), "${module.id}.md")

    private fun readOverride(context: Context, module: PromptModule): String? {
        val file = overrideFile(context, module)
        if (!file.isFile) return null
        return try {
            normalize(file.readText(Charsets.UTF_8)).ifEmpty { null }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "override unreadable: ${module.id} (${t.message})")
            null
        }
    }

    /**
     * Normalize module text: surrounding whitespace is dropped so an editor's
     * incidental blank lines can never change the assembled prompt layout, which
     * the composer owns through [PromptModule.gapBefore].
     */
    private fun normalize(text: String): String = text.trim()
}
