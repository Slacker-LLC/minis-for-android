package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index(value = ["session_id", "sort_order"]),
        Index(value = ["session_id", "request_state", "sort_order"], name = "index_messages_session_request_order")]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    val role: String,
    @ColumnInfo(name = "parts_json") val partsJson: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,        // milliseconds
    @ColumnInfo(name = "token_usage") val tokenUsage: String? = null,
    @ColumnInfo(name = "sort_order") val sortOrder: Int,
    @ColumnInfo(name = "reasoning_content") val reasoningContent: String? = null,
    // iOS parity fields:
    @ColumnInfo(name = "stream_interrupt_count") val streamInterruptCount: Int = 0,
    @ColumnInfo(name = "updated_at") val updatedAt: Long? = null,        // milliseconds
    // [T-error-persist-android] Terminal error sticker for an assistant turn
    // (mirrors iOS messages.error_info / ChatMessage.error). Null for normal
    // rows; device-local, never synced to iCloud.
    @ColumnInfo(name = "error_info") val errorInfo: String? = null,
    /**
     * Model/provider snapshot captured when this message was produced.
     * Nullable so rows written before schema v14 remain readable and can be
     * explicitly marked as estimated by the usage screen.
     */
    @ColumnInfo(name = "model_id") val modelId: String? = null,
    @ColumnInfo(name = "model_display_name") val modelDisplayName: String? = null,
    @ColumnInfo(name = "provider_type") val providerType: String? = null,
    @ColumnInfo(name = "provider_instance_id") val providerInstanceId: String? = null,
    @ColumnInfo(name = "request_state") val requestState: String? = null,
    @ColumnInfo(name = "request_delivery") val requestDelivery: String? = null,
    @ColumnInfo(name = "request_task_id") val requestTaskId: String? = null,
    @ColumnInfo(name = "request_task_generation") val requestTaskGeneration: Int? = null,
    @ColumnInfo(name = "request_run_id") val requestRunId: String? = null,
    @ColumnInfo(name = "request_user_input") val requestUserInput: Boolean? = null,
) {
    val isConversationMessage: Boolean get() = requestState == null || requestRunId != null
}
