package io.rebble.libpebblecommon.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.rebble.libpebblecommon.packets.blobdb.BlobCommand
import kotlin.uuid.Uuid

@Entity
data class BlobDBItem(
    @PrimaryKey val id: Uuid,
    val syncStatus: BlobDBItemSyncStatus,
    val watchIdentifier: String,
    val watchDatabase: BlobCommand.BlobDatabase,
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || this::class != other::class) return false

        other as BlobDBItem

        if (id != other.id) return false
        if (syncStatus != other.syncStatus) return false
        if (watchIdentifier != other.watchIdentifier) return false
        if (watchDatabase != other.watchDatabase) return false
        if (!data.contentEquals(other.data)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + syncStatus.hashCode()
        result = 31 * result + watchIdentifier.hashCode()
        result = 31 * result + watchDatabase.hashCode()
        result = 31 * result + data.contentHashCode()
        return result
    }
}
