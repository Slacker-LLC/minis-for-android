package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * [T-eta-character-cards] One imported character card.
 *
 * Ported from Eta `data/db/CharacterEntity.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. The card itself is stored as its full JSON text rather than as
 * columns, because the card model keeps every field it does not understand and a column per
 * field would quietly drop them.
 */
@Entity(tableName = "characters")
data class CharacterEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "card_json") val cardJson: String,
    @ColumnInfo(name = "avatar_path") val avatarPath: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
