package com.openminis.app.voicecall

/**
 * Which chat a voice call opens in. Pure so the "new chat vs continue" setting
 * can be tested without Compose or navigation.
 */
internal object VoiceEntryTarget {

    /**
     * Deep-link entry (app-icon quick action): a fresh draft when [newChat] is
     * on, otherwise the remembered voice session, falling back to a fresh draft.
     */
    fun forDeepLink(newChat: Boolean, remembered: String?, newDraftId: () -> String): String =
        if (newChat) newDraftId() else remembered ?: newDraftId()

    /**
     * Composer entry (the voice button inside a chat): returns the draft id to
     * move to, or null to start the call in the current chat. A blank draft is
     * reused instead of stacking another, and an action already scoped to a
     * session (deep link, voice task switch) is never redirected.
     */
    fun redirectFromComposer(
        newChat: Boolean,
        actionScopedToSession: Boolean,
        currentIsBlankDraft: Boolean,
        newDraftId: () -> String,
    ): String? =
        if (newChat && !actionScopedToSession && !currentIsBlankDraft) newDraftId() else null
}
