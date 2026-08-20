package com.openminis.app.voice

import android.service.voice.VoiceInteractionService

/**
 * Lets OpenMinis Pet be registered as the system default digital assistant
 * (RoleManager.ROLE_ASSIST). The service itself is a declaration-only
 * anchor; interaction is handled by [AssistSession], which opens the app
 * when the assistant is invoked (long-press Home / voice).
 */
class AssistService : VoiceInteractionService()
