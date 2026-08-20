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
            // English + localized keywords: OEM/localized builds report status
            // text in other languages, so match common Chinese terms too
            // (the canonical fix would be a structured status code, but the
            // bridge layer receives free text today).
            status.contains("fail") || status.contains("error") || status.contains("cancel") ||
                status.contains("失败") || status.contains("出错") || status.contains("取消") -> PetState.FAILED
            status.contains("review") || status.contains("inspect") || status.contains("check") ||
                status.contains("审查") || status.contains("检查") -> PetState.REVIEW
            status.contains("wait") || status.contains("queue") || status.contains("pending") ||
                status.contains("等待") || status.contains("排队") -> PetState.WAITING
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
