package com.openminis.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BotDao {
    @Query("SELECT * FROM bots ORDER BY updated_at DESC")
    fun observeBots(): Flow<List<BotEntity>>

    @Query("SELECT * FROM bots ORDER BY updated_at DESC")
    suspend fun listBots(): List<BotEntity>

    @Query("SELECT COUNT(*) FROM bots")
    suspend fun countBots(): Int

    @Query("SELECT * FROM bots WHERE id = :id")
    suspend fun getBot(id: String): BotEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertBot(bot: BotEntity)

    @Query("UPDATE bots SET name = :name, system_prompt = :systemPrompt, model_binding = :modelBinding, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateBotProfile(
        id: String,
        name: String,
        systemPrompt: String?,
        modelBinding: String?,
        updatedAt: Long,
    )

    @Query("UPDATE bots SET enabled = :enabled, updated_at = :updatedAt WHERE id = :id")
    suspend fun setBotEnabled(id: String, enabled: Boolean, updatedAt: Long)

    @Query("UPDATE sessions SET bot_id = NULL WHERE bot_id = :botId")
    suspend fun clearBotFromSessions(botId: String)

    @Delete
    suspend fun deleteBot(bot: BotEntity)
}
