package io.github.slackerllc.minis.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Durable replay window for the native agent's append-only session event log.
 *
 * Messages remain the durable conversation snapshot. These rows preserve the
 * ordered live transitions between snapshots (assistant chunks, tool calls and
 * results, turn boundaries) so a Web client can resume after a reconnect or a
 * process restart without polling and rebuilding a whole conversation.
 *
 * The composite key gives each session its own monotonic sequence namespace,
 * matching DeepSeek Harness' SessionEvent contract. Retention is enforced by
 * [ChatDao.trimSessionEvents] after append batches; this table is a bounded
 * replay log, not an unbounded second copy of chat history.
 */
@Entity(
    tableName = "session_events",
    primaryKeys = ["session_id", "seq"],
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["session_id", "seq"], name = "index_session_events_session_id_seq"),
        Index(value = ["created_at"], name = "index_session_events_created_at"),
    ],
)
data class SessionEventEntity(
    @ColumnInfo(name = "session_id") val sessionId: String,
    val seq: Long,
    val type: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
