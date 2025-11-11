package io.rebble.libpebblecommon.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.WatchPrefs
import io.rebble.libpebblecommon.database.entity.WatchPrefItem
import io.rebble.libpebblecommon.database.entity.WatchPrefItemDao
import io.rebble.libpebblecommon.database.entity.WatchPrefItemSyncEntity
import io.rebble.libpebblecommon.database.entity.asWatchPrefItem
import io.rebble.libpebblecommon.packets.blobdb.BlobResponse
import io.rebble.libpebblecommon.services.blobdb.DbWrite
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Dao
interface WatchPrefRealDao : WatchPrefItemDao {
    @Transaction
    override suspend fun handleWrite(write: DbWrite, transport: String): BlobResponse.BlobStatus {
        val writeItem = write.asWatchPrefItem()
        if (writeItem == null) {
            logger.e { "Couldn't decode watch pref item from blobdb write: $write" }
            return BlobResponse.BlobStatus.Success
        }
        logger.v { "blobdb handleWrite: $writeItem" }
        insertOrReplace(writeItem)
        markSyncedToWatch(
            WatchPrefItemSyncEntity(
                recordId = writeItem.id,
                transport = transport,
                watchSynchHashcode = writeItem.recordHashCode(),
            )
        )
        return BlobResponse.BlobStatus.Success
    }

    @Query("SELECT * FROM WatchPrefItemEntity")
    fun getAllFlow(): Flow<List<WatchPrefItem>>

    companion object {
        private val logger = Logger.withTag("WatchPrefRealDao")
    }
}

data class WatchPreference(
    val key: String,
    val value: String,
)

class RealWatchPrefs(
    private val watchPrefRealDao: WatchPrefRealDao,
) : WatchPrefs {
    override val watchPrefs: Flow<List<WatchPreference>> = watchPrefRealDao.getAllFlow().map { prefs ->
        prefs.map { pref ->
            WatchPreference(key = pref.id, value = pref.value)
        }
    }

    override fun setWatchPref(watchPref: WatchPreference) {
        TODO("Not yet implemented")
    }
}