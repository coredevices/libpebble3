package io.rebble.libpebblecommon.database.dao

import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.database.entity.LockerEntryDao
import io.rebble.libpebblecommon.database.entity.LockerEntryEntity
import io.rebble.libpebblecommon.database.entity.LockerEntrySyncEntity
import kotlinx.coroutines.flow.Flow
import kotlin.uuid.Uuid

class FakeLockerEntryDao: LockerEntryDao {
    override fun dirtyRecordsForWatchInsert(
        identifier: String,
        timestampMs: Long,
        insertOnlyAfterMs: Long
    ): Flow<List<LockerEntryEntity>> {
        TODO("Not yet implemented")
    }

    override fun dirtyRecordsForWatchDelete(
        identifier: String,
        timestampMs: Long
    ): Flow<List<LockerEntryEntity>> {
        TODO("Not yet implemented")
    }

    override suspend fun deleteStaleRecords(timestampMs: Long) {
        TODO("Not yet implemented")
    }

    override suspend fun markSyncedToWatch(syncRecord: LockerEntrySyncEntity) {
        TODO("Not yet implemented")
    }

    override suspend fun markDeletedFromWatch(syncRecord: LockerEntrySyncEntity) {
        TODO("Not yet implemented")
    }

    override fun existsOnWatch(
        identifier: String,
        primaryKey: Uuid
    ): Flow<Boolean> {
        TODO("Not yet implemented")
    }

    override suspend fun markForDeletion(id: Uuid) {
        TODO("Not yet implemented")
    }

    override suspend fun markAllForDeletion(ids: List<Uuid>) {
        TODO("Not yet implemented")
    }

    override suspend fun markAllDeletedFromWatch(identifier: String) {
        TODO("Not yet implemented")
    }

    override suspend fun deleteSyncRecordsForDevicesWhichDontExist() {
        TODO("Not yet implemented")
    }

    override suspend fun getEntry(id: Uuid): LockerEntry? {
        TODO("Not yet implemented")
    }

    override fun getEntryFlow(id: Uuid): Flow<LockerEntry?> {
        TODO("Not yet implemented")
    }

    override suspend fun insertOrReplace(item: LockerEntryEntity) {
        TODO("Not yet implemented")
    }

    override suspend fun insertOrReplaceAll(items: List<LockerEntryEntity>) {
        TODO("Not yet implemented")
    }
}