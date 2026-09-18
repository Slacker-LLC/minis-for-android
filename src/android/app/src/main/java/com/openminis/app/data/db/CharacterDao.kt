package com.openminis.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** [T-eta-character-cards] Storage for imported character cards. */
@Dao
interface CharacterDao {
    @Query("SELECT * FROM characters ORDER BY updated_at DESC")
    suspend fun characters(): List<CharacterEntity>

    @Query("SELECT * FROM characters ORDER BY updated_at DESC")
    fun observeCharacters(): Flow<List<CharacterEntity>>

    @Query("SELECT * FROM characters WHERE id = :id")
    suspend fun character(id: String): CharacterEntity?

    @Query("SELECT COUNT(*) FROM characters")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCharacter(entity: CharacterEntity)

    @Query("DELETE FROM characters WHERE id = :id")
    suspend fun deleteCharacter(id: String)
}
