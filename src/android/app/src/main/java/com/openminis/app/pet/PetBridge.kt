package com.openminis.app.pet

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/** Small API used by OpenMinis runtime code without depending on overlay internals. */
object PetBridge {
    private const val TAG = "PetBridge"

    fun setState(context: Context, state: PetState) {
        if (!PetPreferences.isEnabled(context)) return
        val intent = Intent(context, PetOverlayService::class.java).apply {
            action = PetOverlayService.ACTION_SET_STATE
            putExtra(PetOverlayService.EXTRA_STATE, state.name)
        }
        startSafely(context, intent)
    }

    fun updateAgentStatus(context: Context, sessionCount: Int, toolStatus: String?) {
        if (!PetPreferences.isEnabled(context)) return
        val status = toolStatus.orEmpty().lowercase()
        val state = when {
            status.contains("fail") || status.contains("error") || status.contains("cancel") -> PetState.FAILED
            status.contains("review") || status.contains("inspect") || status.contains("check") -> PetState.REVIEW
            status.contains("wait") || status.contains("queue") || status.contains("pending") -> PetState.WAITING
            sessionCount > 0 -> PetState.RUNNING
            else -> PetState.IDLE
        }
        setState(context, state)
    }

    fun startIfEnabled(context: Context) {
        if (!PetPreferences.isEnabled(context)) return
        startSafely(context, Intent(context, PetOverlayService::class.java).apply {
            action = PetOverlayService.ACTION_START
        })
    }

    fun reload(context: Context) {
        if (!PetPreferences.isEnabled(context)) return
        startSafely(context, Intent(context, PetOverlayService::class.java).apply {
            action = PetOverlayService.ACTION_RELOAD
        })
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, PetOverlayService::class.java))
    }

    private fun startSafely(context: Context, intent: Intent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
            else context.startService(intent)
        } catch (t: Throwable) {
            // Never let the optional pet runtime crash an agent turn on OEM ROMs
            // that reject an FGS start from a background transition.
            Log.w(TAG, "Unable to start/update pet overlay: ${t.message}")
        }
    }
}
