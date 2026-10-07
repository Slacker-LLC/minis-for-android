package com.openminis.app.config

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How much the app asks before the agent changes its settings.
 *  - [ASK]   every change is confirmed (the original behaviour);
 *  - [SMART] ordinary settings go through, secrets and risky changes are still confirmed;
 *  - [FULL]  nothing is asked. Fields that refuse writes (the permission switches themselves, this mode)
 *            still refuse: they have no writer the agent can reach.
 *
 * Only the user changes this, from Settings → Permissions: there is no config field that writes it, and
 * backups do not carry it, so neither the agent nor a restored package can raise its own autonomy.
 */
enum class AutonomyMode(val wire: String) {
    ASK("ask"), SMART("smart"), FULL("full");

    companion object {
        /** What a fresh install uses: the user asked for no one-time popups, so nothing is asked until they pick otherwise. */
        val DEFAULT = FULL

        /** A missing value is [DEFAULT]; an unreadable one fails to the cautious middle. */
        fun fromWire(value: String?): AutonomyMode =
            if (value == null) DEFAULT else entries.firstOrNull { it.wire == value } ?: SMART
    }
}

object AutonomyStore {
    private const val PREFS = "autonomy_prefs"
    private const val KEY = "mode"
    private var prefs: SharedPreferences? = null

    private val _mode = MutableStateFlow(AutonomyMode.DEFAULT)
    val mode: StateFlow<AutonomyMode> = _mode.asStateFlow()

    val current: AutonomyMode get() = _mode.value

    /** Call once early, from MinisApp.onCreate. Idempotent. */
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _mode.value = AutonomyMode.fromWire(p.getString(KEY, null))
    }

    fun setMode(value: AutonomyMode) {
        prefs?.edit { putString(KEY, value.wire) }
        _mode.value = value
    }
}

/** The decision, separate from the store so it can be tested. */
object AutonomyPolicy {
    /**
     * Whether a config change must be confirmed by the user. [touchesSecret] is a credential or token field.
     * SMART asks for anything that is not an ordinary, non-secret setting.
     */
    fun configNeedsConfirmation(mode: AutonomyMode, risks: List<ConfigRisk>, touchesSecret: Boolean): Boolean =
        when (mode) {
            AutonomyMode.ASK -> true
            AutonomyMode.FULL -> false
            AutonomyMode.SMART -> touchesSecret || risks.any { it != ConfigRisk.NORMAL }
        }
}
