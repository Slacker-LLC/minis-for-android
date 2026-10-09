package com.openminis.app.provider.quota

import java.util.concurrent.ConcurrentHashMap

/** Which provider instance each chat last sent to, so `provider_quota` can answer for "the model I am talking to". */
object ActiveChatProvider {
    private val bySession = ConcurrentHashMap<String, String>()

    fun set(sessionId: String, instanceId: String?) {
        if (sessionId.isEmpty()) return
        if (instanceId == null) bySession.remove(sessionId) else bySession[sessionId] = instanceId
    }

    fun get(sessionId: String): String? = bySession[sessionId]
}
