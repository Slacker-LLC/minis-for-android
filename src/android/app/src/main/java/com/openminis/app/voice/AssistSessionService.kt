package com.openminis.app.voice

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

class AssistSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(bindArgs: Bundle?): VoiceInteractionSession =
        AssistSession(applicationContext, this)
}
