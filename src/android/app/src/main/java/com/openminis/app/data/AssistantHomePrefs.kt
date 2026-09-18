package com.openminis.app.data

import android.content.Context
import com.openminis.app.ui.home.AssistantQuickAction
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [T-android-assistant-home] Persisted state of the assistant home page: which
 * quick actions its 2×2 grid shows, and whether the page is the app's start
 * screen. Both values live in a [StateFlow] so the home page, the session-list
 * entry and the launch resolver all read the same in-process truth.
 *
 * The start page is deliberately a separate flag rather than a new
 * `launch_session` mode: `launch_session = 3` already means "do not auto-open a
 * session" and is set by users (and by the hang/crash circuit breakers), so
 * redefining it would silently move every one of those launches to a new
 * screen. With this flag off nothing about the existing launch behaviour
 * changes.
 */
internal object AssistantHomePrefs {
    private const val PREFS = "minis_assistant_home_prefs"

    /** Comma-joined [AssistantQuickAction.id] list, in display order. */
    const val KEY_ACTIONS = "assistant_home_actions"

    /** When true the app opens on the assistant home page instead of the list. */
    const val KEY_START_PAGE = "assistant_home_start_page"

    private val _actions = MutableStateFlow(AssistantQuickAction.DEFAULT_ORDER)
    val actions: StateFlow<List<AssistantQuickAction>> = _actions.asStateFlow()

    private val _startPage = MutableStateFlow(false)
    val startPage: StateFlow<Boolean> = _startPage.asStateFlow()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun prime(context: Context) {
        val store = prefs(context)
        _actions.value = AssistantQuickAction.parseSelection(store.getString(KEY_ACTIONS, null))
        _startPage.value = store.getBoolean(KEY_START_PAGE, false)
    }

    fun setActions(context: Context, actions: List<AssistantQuickAction>) {
        val normalized = AssistantQuickAction.parseSelection(AssistantQuickAction.serialize(actions))
        prefs(context).edit()
            .putString(KEY_ACTIONS, AssistantQuickAction.serialize(normalized))
            .apply()
        _actions.value = normalized
    }

    fun setStartPage(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_START_PAGE, enabled).apply()
        _startPage.value = enabled
    }
}
