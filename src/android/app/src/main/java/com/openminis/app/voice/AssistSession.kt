package com.openminis.app.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import com.openminis.app.MainActivity

/**
 * Minimal assistant session: invoking the assistant opens the app's main
 * chat UI and immediately finishes the session. Keeps the surface small —
 * OpenMinis Pet is a text-first assistant, so long-press Home = open the app.
 */
class AssistSession(
    private val appContext: Context,
    service: AssistSessionService,
) : VoiceInteractionSession(service) {

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        runCatching {
            val intent = Intent(appContext, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            appContext.startActivity(intent)
        }
        finish()
    }
}
