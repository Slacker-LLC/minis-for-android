package com.openminis.app.config

import com.openminis.app.ui.chat.ChatViewModelStore

/**
 * Which chat a `session.*` config field acts on. The CLI call carries the chat session it came
 * from; the bridge sets it around the field's read/write so the value that is audited and the
 * session that is changed are the same one, whatever chat happens to be on screen later (a write can
 * wait minutes for the user's confirmation). Calls with no session (a terminal) fall back to the
 * foreground chat, as before.
 */
internal object ConfigCallSession {
    private val current = ThreadLocal<String?>()

    /** Runs [block] (synchronously, on this thread) with [sessionId] as the target. */
    inline fun <T> with(sessionId: String?, block: () -> T): T {
        val previous = enter(sessionId)
        try {
            return block()
        } finally {
            restore(previous)
        }
    }

    fun enter(sessionId: String?): String? {
        val previous = current.get()
        current.set(sessionId?.takeIf { it.isNotBlank() })
        return previous
    }

    fun restore(previous: String?) = current.set(previous)

    fun targetSession(): String? = current.get()?.let { ChatViewModelStore.resolvePersistedId(it) }
        ?: ChatViewModelStore.activeSessionId
}
