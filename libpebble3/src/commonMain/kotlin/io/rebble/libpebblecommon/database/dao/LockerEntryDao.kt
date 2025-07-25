package io.rebble.libpebblecommon.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.database.entity.LockerEntryDao
import io.rebble.libpebblecommon.locker.AppType
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

@Dao
interface LockerEntryRealDao : LockerEntryDao {
    @Query("SELECT * FROM LockerEntryEntity ORDER BY orderIndex ASC, title ASC")
    fun getAllFlow(): Flow<List<LockerEntry>>

    @Transaction
    suspend fun insertOrReplaceAndOrder(entry: LockerEntry, syncLimit: Int) {
        insertOrReplace(entry)
        updateOrder(entry.type)
        updateSync(syncLimit)
    }

    @Transaction
    suspend fun insertOrReplaceAndOrder(entries: List<LockerEntry>, syncLimit: Int) {
        insertOrReplace(entries)
        AppType.entries.forEach {
            updateOrder(it.code)
        }
        updateSync(syncLimit)
    }

    @Transaction
    suspend fun setOrder(id: Uuid, orderIndex: Int, syncLimit: Int) {
        updateOrder(id, orderIndex)
        // Could lookup which type it is I guess
        AppType.entries.forEach {
            updateOrder(it.code)
        }
        updateSync(syncLimit)
    }

    @Query("SELECT * FROM LockerEntryEntity WHERE deleted = 0")
    suspend fun getAll(): List<LockerEntry>

    @Query("""
        UPDATE LockerEntryEntity
        SET orderIndex = :orderIndex
        WHERE id = :id
    """)
    suspend fun updateOrder(id: Uuid, orderIndex: Int)

    @Query("""
        WITH Ordered AS (
            SELECT
                id,
                ROW_NUMBER() OVER (ORDER BY orderIndex ASC, title ASC) - 1 AS new_order
            FROM LockerEntryEntity
            WHERE deleted = 0
            AND type = :type
        )
        UPDATE LockerEntryEntity
        SET orderIndex = (
            SELECT new_order
            FROM Ordered
            WHERE Ordered.id = LockerEntryEntity.id
        )
        WHERE LockerEntryEntity.id IN (SELECT id FROM Ordered)
        AND orderIndex IS NOT (
            SELECT new_order
            FROM Ordered
            WHERE Ordered.id = LockerEntryEntity.id
        )
    """)
    suspend fun updateOrder(type: String)

    @Query("""
        UPDATE LockerEntryEntity
        SET sync = CASE
            WHEN orderIndex < :syncLimit THEN 1
            ELSE 0
        END
    """)
    suspend fun updateSync(syncLimit: Int)
}
