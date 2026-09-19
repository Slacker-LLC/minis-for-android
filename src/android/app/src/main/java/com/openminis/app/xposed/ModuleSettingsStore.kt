package com.openminis.app.xposed

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [T-eta-xposed-groups] The app side of the module's settings, and the only writer of them.
 *
 * Ported from Eta `config/Prefs.kt` and the settings store behind its module screen
 * (Mangi-11/Eta @ c15de97); attribution in THIRD_PARTY_LICENSES.md.
 *
 * These switches do not live in this app's own SharedPreferences. A hooked process reads them
 * through the framework (`XposedInterface.getRemotePreferences`), and the framework serves what
 * the module app committed through its service (`XposedService.getRemotePreferences`) - a file
 * written with `Context.getSharedPreferences` never reaches those processes. Measured on the
 * Xiaomi 24129PN74C: with the file-based writer, system_server logged
 * `settings reader=attached powerKeyTarget=<unset>` and every group fell back to its own
 * default, so "the user turned it on" and "nothing was ever written" looked identical from the
 * outside.
 *
 * The local preferences stay as a mirror, in the same group, with two jobs: they carry values the
 * user set before the framework was connected ([reconcile] pushes them into the framework on the
 * first bind), and they keep the screen showing a value while the framework is away. A write only
 * reports success when the framework accepted the commit, so the screen never shows a switch as
 * on while the hooks read something else.
 */
object ModuleSettingsStore {

    /** The switches the settings screen owns, in the order it shows them. */
    val SWITCHES: List<String> = listOf(
        ModulePrefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH,
        ModulePrefs.Keys.DOUBLE_FINGER_CIRCLE_TO_SEARCH,
        ModulePrefs.Keys.HOTWORD_SELF_HEAL,
    )

    /** Settings that travel as strings rather than switches. */
    val STRING_KEYS: List<String> = listOf(ModulePrefs.Keys.POWER_KEY_ASSISTANT_TARGET)

    private val _connected = MutableStateFlow(false)

    /** Whether the framework handed this process the module's settings service. */
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    @Volatile
    private var service: XposedService? = null

    @Volatile
    private var remote: SharedPreferences? = null

    /**
     * Called when the framework binds its service. The first bind also migrates the values this
     * app wrote while it was the only place the switches existed.
     */
    fun attachService(context: Context, bound: XposedService) {
        val preferences = runCatching { bound.getRemotePreferences(ModulePrefs.GROUP) }.getOrNull()
        if (preferences == null) {
            _connected.value = false
            return
        }
        service = bound
        remote = preferences
        runCatching { reconcile(local(context), preferences, SWITCHES, STRING_KEYS) }
        _connected.value = true
    }

    /** Called when the framework reports the service gone; only the current instance may clear. */
    fun detachService(dead: XposedService) {
        if (service === dead) {
            service = null
            remote = null
            _connected.value = false
        }
    }

    fun isEnabled(context: Context, key: String): Boolean =
        readBoolean(remote, local(context), key, ModulePrefs.defaultOf(key))

    /** False when the framework is absent or refused the commit; the caller must not move the UI. */
    fun setEnabled(context: Context, key: String, enabled: Boolean): Boolean =
        writeBoolean(remote, local(context), key, enabled)

    fun assistantTarget(context: Context): PowerAssistantTarget = PowerAssistantTarget.parse(
        readString(remote, local(context), ModulePrefs.Keys.POWER_KEY_ASSISTANT_TARGET),
    )

    fun setAssistantTarget(context: Context, target: PowerAssistantTarget): Boolean =
        writeString(remote, local(context), ModulePrefs.Keys.POWER_KEY_ASSISTANT_TARGET, target.wire)

    private fun local(context: Context): SharedPreferences = context.applicationContext
        .getSharedPreferences(ModulePrefs.GROUP, Context.MODE_PRIVATE)

    /**
     * While the framework is connected its store is what the hooks read, so it is what is
     * reported. The mirror is only consulted when there is no framework to ask.
     */
    internal fun readBoolean(
        remote: SharedPreferences?,
        mirror: SharedPreferences?,
        key: String,
        default: Boolean,
    ): Boolean = if (remote != null) {
        remote.getBoolean(key, default)
    } else {
        mirror?.getBoolean(key, default) ?: default
    }

    internal fun readString(remote: SharedPreferences?, mirror: SharedPreferences?, key: String): String? =
        if (remote != null) remote.getString(key, null) else mirror?.getString(key, null)

    /**
     * KTX's `edit { }` returns Unit, so it cannot report what `commit` answered - and a write the
     * framework refused must not be mirrored as if it had landed. The direct editor is the point
     * of this function, not an oversight.
     */
    @Suppress("UseKtx")
    internal fun writeBoolean(
        remote: SharedPreferences?,
        mirror: SharedPreferences?,
        key: String,
        value: Boolean,
    ): Boolean {
        val target = remote ?: return false
        val committed = runCatching {
            target.edit().putBoolean(key, value).commit()
        }.getOrDefault(false)
        if (committed) runCatching { mirror?.edit()?.putBoolean(key, value)?.apply() }
        return committed
    }

    /** See [writeBoolean]: the commit answer is the contract, and KTX hides it. */
    @Suppress("UseKtx")
    internal fun writeString(
        remote: SharedPreferences?,
        mirror: SharedPreferences?,
        key: String,
        value: String,
    ): Boolean {
        val target = remote ?: return false
        val committed = runCatching {
            target.edit().putString(key, value).commit()
        }.getOrDefault(false)
        if (committed) runCatching { mirror?.edit()?.putString(key, value)?.apply() }
        return committed
    }

    /**
     * A local value means the user chose it while this app was the only writer, so it wins and is
     * pushed into the framework. A value only the framework knows is copied back, so the screen
     * and the hooks agree on what is in force.
     */
    internal fun reconcile(
        mirror: SharedPreferences?,
        remote: SharedPreferences?,
        booleanKeys: List<String>,
        stringKeys: List<String>,
    ) {
        if (mirror == null || remote == null) return
        val remoteEditor = remote.edit()
        val mirrorEditor = mirror.edit()
        var writeRemote = false
        var writeMirror = false
        booleanKeys.forEach { key ->
            when {
                mirror.contains(key) -> {
                    remoteEditor.putBoolean(key, mirror.getBoolean(key, ModulePrefs.defaultOf(key)))
                    writeRemote = true
                }
                remote.contains(key) -> {
                    mirrorEditor.putBoolean(key, remote.getBoolean(key, ModulePrefs.defaultOf(key)))
                    writeMirror = true
                }
            }
        }
        stringKeys.forEach { key ->
            val localValue = mirror.getString(key, null)
            val remoteValue = remote.getString(key, null)
            when {
                localValue != null -> {
                    remoteEditor.putString(key, localValue)
                    writeRemote = true
                }
                remoteValue != null -> {
                    mirrorEditor.putString(key, remoteValue)
                    writeMirror = true
                }
            }
        }
        if (writeRemote) runCatching { remoteEditor.commit() }
        if (writeMirror) runCatching { mirrorEditor.apply() }
    }
}
