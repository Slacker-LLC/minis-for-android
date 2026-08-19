package com.openminis.app.pet

import android.content.Context

object PetPreferences {
    private const val FILE = "openminis_pet_runtime"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SELECTED = "selected_pet_id"
    private const val KEY_SCALE = "scale"
    private const val KEY_SPEED = "speed"
    private const val KEY_X = "overlay_x"
    private const val KEY_Y = "overlay_y"

    // Behaviour toggles. Defaults keep the pet lively out of the box but never
    // let it cover content indefinitely: it wanders, snaps to an edge, and
    // tucks itself away when the user stops interacting.
    private const val KEY_WANDER = "behaviour_wander"
    private const val KEY_EDGE_SNAP = "behaviour_edge_snap"
    private const val KEY_AUTO_HIDE = "behaviour_auto_hide"
    private const val KEY_BUBBLE = "behaviour_bubble"
    private const val KEY_TAP_OPENS_APP = "behaviour_tap_opens_app"
    private const val KEY_CHAT_SESSION = "chat_session_id"

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)
    fun setEnabled(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()

    fun selectedPetId(context: Context): String? = prefs(context).getString(KEY_SELECTED, null)
    fun setSelectedPetId(context: Context, id: String?) = prefs(context).edit().putString(KEY_SELECTED, id).apply()

    fun scale(context: Context): Float = prefs(context).getFloat(KEY_SCALE, 1.0f).coerceIn(0.5f, 2.0f)
    fun setScale(context: Context, value: Float) = prefs(context).edit().putFloat(KEY_SCALE, value.coerceIn(0.5f, 2.0f)).apply()

    fun speed(context: Context): Float = prefs(context).getFloat(KEY_SPEED, 1.0f).coerceIn(0.5f, 2.0f)
    fun setSpeed(context: Context, value: Float) = prefs(context).edit().putFloat(KEY_SPEED, value.coerceIn(0.5f, 2.0f)).apply()

    fun position(context: Context): Pair<Int, Int> = prefs(context).let { it.getInt(KEY_X, 24) to it.getInt(KEY_Y, 180) }
    fun setPosition(context: Context, x: Int, y: Int) = prefs(context).edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply()

    fun wanderEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_WANDER, true)
    fun setWanderEnabled(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_WANDER, value).apply()

    fun edgeSnapEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_EDGE_SNAP, true)
    fun setEdgeSnapEnabled(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_EDGE_SNAP, value).apply()

    fun autoHideEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_HIDE, true)
    fun setAutoHideEnabled(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_AUTO_HIDE, value).apply()

    fun bubbleEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_BUBBLE, true)
    fun setBubbleEnabled(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_BUBBLE, value).apply()

    fun tapOpensApp(context: Context): Boolean = prefs(context).getBoolean(KEY_TAP_OPENS_APP, true)
    fun setTapOpensApp(context: Context, value: Boolean) = prefs(context).edit().putBoolean(KEY_TAP_OPENS_APP, value).apply()

    /**
     * The chat session pet conversations are written into, so they show up in
     * the app's normal session list instead of vanishing with the bubble.
     */
    fun chatSessionId(context: Context): String? = prefs(context).getString(KEY_CHAT_SESSION, null)
    fun setChatSessionId(context: Context, id: String?) = prefs(context).edit().putString(KEY_CHAT_SESSION, id).apply()
}
