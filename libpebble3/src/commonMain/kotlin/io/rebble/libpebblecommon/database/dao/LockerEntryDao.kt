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
    @Query("""
        SELECT * FROM LockerEntryEntity
        WHERE deleted = 0
        AND type = :type
        AND (:searchQuery IS NULL OR title LIKE '%' || :searchQuery || '%' OR developerName LIKE '%' || :searchQuery || '%')
        ORDER BY orderIndex ASC, title ASC
        LIMIT :limit
    """)
    fun getAllFlow(type: String, searchQuery: String?, limit: Int): Flow<List<LockerEntry>>

    @Query("""
        SELECT id FROM LockerEntryEntity
        WHERE deleted = 0
        AND type = :type
        ORDER BY orderIndex ASC
        LIMIT :limit
    """)
    fun getAppOrderFlow(type: String, limit: Int): Flow<List<Uuid>>

    data class DbAppBasicProperties(
        val id: Uuid,
        val title: String,
        val type: String,
        val developerName: String,
    )

    @Query("""
        SELECT id, type, title, developerName FROM LockerEntryEntity WHERE deleted = 0
    """)
    fun getAllBasicInfoFlow(): Flow<List<DbAppBasicProperties>>

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
        val entry = getEntry(id) ?: return
        val oldOrder = entry.orderIndex
        if (oldOrder > orderIndex) {
            incrementOrderIndexes(orderIndex, oldOrder, entry.type)
        } else {
            decrementOrderIndexes(oldOrder, orderIndex, entry.type)
        }
        updateOrder(id, orderIndex)
        updateOrder(entry.type)
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
        UPDATE LockerEntryEntity
        SET orderIndex = orderIndex + 1
        WHERE orderIndex BETWEEN :fromIndex AND :toIndex AND type = :type;
    """)
    suspend fun incrementOrderIndexes(fromIndex: Int, toIndex: Int, type: String)

    @Query("""
        UPDATE LockerEntryEntity
        SET orderIndex = orderIndex - 1
        WHERE orderIndex BETWEEN :fromIndex AND :toIndex AND type = :type;
    """)
    suspend fun decrementOrderIndexes(fromIndex: Int, toIndex: Int, type: String)


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
            WHEN systemApp = 1 THEN 0
            WHEN orderIndex < :syncLimit THEN 1
            ELSE 0
        END
    """)
    suspend fun updateSync(syncLimit: Int)
}
