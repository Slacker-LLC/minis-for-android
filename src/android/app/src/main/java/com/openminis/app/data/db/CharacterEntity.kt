package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * [T-eta-character-cards] One imported character card.
 *
 * Ported from Eta `data/db/CharacterEntity.kt` (Mangi-11/Eta @ c15de97); attribution in
 * THIRD_PARTY_LICENSES.md. The card itself is stored as its full JSON text rather than as
 * columns, because the card model keeps every field it does not understand and a column per
 * field would quietly drop them.
 *
 * [T-android-room-migration-index] The index the `MIGRATION_20_21` statement creates is
 * declared here as well. Room validates a migrated database against the entity, so an index
 * the migration adds and the entity does not declare is not "extra": it is a schema
 * mismatch, and on a device that upgrades rather than installs fresh it aborts the open with
 * `Migration didn't properly handle: characters` — which is exactly what the real-device
 * pass caught, while a fresh install never saw it because it takes the create path.
 */
@Entity(
    tableName = "characters",
    indices = [Index(value = ["updated_at"])],
)
data class CharacterEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "card_json") val cardJson: String,
    @ColumnInfo(name = "avatar_path") val avatarPath: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
)
