package com.openminis.app.voice

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import com.openminis.app.MainActivity

/**
 * Assistant session: invoking the assistant opens the app at a fresh voice
 * chat and immediately finishes the session. Long-press assistant = "talk to
 * Minis" — the deep link carries the voice action so the Composer opens its
 * inline voice panel (START_VOICE) instead of showing the plain text input.
 */
class AssistSession(
    private val appContext: Context,
    service: AssistSessionService,
) : VoiceInteractionSession(service) {

    companion object {
        /** Debounce: repeated assistant invocations within this window are
         *  collapsed into one launch (long-press Home mashing). */
        private const val LAUNCH_DEBOUNCE_MS = 1_500L
        @Volatile private var lastLaunchAt = 0L
        private const val VOICE_ACTION_URI = "minis://action/voice"
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val now = System.currentTimeMillis()
        if (now - lastLaunchAt < LAUNCH_DEBOUNCE_MS) {
            finish()
            return
        }
        lastLaunchAt = now
        runCatching {
            val intent = Intent(appContext, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse(VOICE_ACTION_URI))
                // MainActivity is singleTask. CLEAR_TOP makes every system
                // assistant gesture bring the existing chat task forward
                // rather than layering another task behind the session.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            appContext.startActivity(intent)
        }
        finish()
    }
}
