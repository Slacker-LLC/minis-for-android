package com.openminis.app.ui.chat

import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.repository.ChatRepository
import org.json.JSONArray

/** Loads the complete transcript through the repository's bounded CursorWindow pages. */
internal object SessionHistoryLoader {
    data class History(
        val rows: List<MessageEntity>,
        val parts: Map<String, Result<JSONArray>>,
    )

    suspend fun load(repository: ChatRepository, sessionId: String): History {
        // A display window is not a context boundary: tool pairs and compact
        // anchors may precede it. Keep all rows available to edit/retry/resume.
        val rows = repository.loadMessages(sessionId)
        return History(rows, rows.associate { it.id to runCatching { JSONArray(it.partsJson) } })
    }
}
