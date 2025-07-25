package io.rebble.libpebblecommon.database.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Index
import coredev.BlobDatabase
import coredev.GenerateRoomEntity
import io.rebble.libpebblecommon.database.MillisecondInstant
import io.rebble.libpebblecommon.database.dao.BlobDbItem
import io.rebble.libpebblecommon.metadata.WatchType
import io.rebble.libpebblecommon.packets.ProtocolCapsFlag
import io.rebble.libpebblecommon.packets.blobdb.AppMetadata
import io.rebble.libpebblecommon.structmapper.SUUID
import io.rebble.libpebblecommon.structmapper.StructMapper
import kotlinx.serialization.Serializable
import kotlin.uuid.Uuid

@GenerateRoomEntity(
    primaryKey = "id",
    databaseId = BlobDatabase.App,
    windowBeforeSecs = -1,
    windowAfterSecs = -1,
    onlyInsertAfter = false,
    sendDeletions = true,
)
data class LockerEntry(
    val id: Uuid,
    val version: String,
    val title: String,
    val type: String,
    val developerName: String,
    val configurable: Boolean,
    val pbwVersionCode: String,
    val sideloaded: Boolean = false,
    val sideloadeTimestamp: MillisecondInstant? = null,
    @Embedded
    val appstoreData: LockerEntryAppstoreData? = null,
    val platforms: List<LockerEntryPlatform>,

    @ColumnInfo(defaultValue = "0")
    val orderIndex: Int = 0,
) : BlobDbItem {
    override fun key(): UByteArray = SUUID(StructMapper(), id).toBytes()

    override fun value(platform: WatchType, capabilities: Set<ProtocolCapsFlag>): UByteArray? {
        return asMetadata(platform)?.toBytes()
    }

    // Only some fields should trigger a watch resync if changed:
    override fun recordHashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + version.hashCode()
        result = 31 * result + title.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + developerName.hashCode()
        result = 31 * result + pbwVersionCode.hashCode()
        result = 31 * result + sideloaded.hashCode()
        result = 31 * result + (sideloadeTimestamp?.hashCode() ?: 0)
        result = 31 * result + platforms.recordHashCode()
        return result
    }
}

fun LockerEntry.asMetadata(platform: WatchType): AppMetadata? {
    val compatiblePlatforms = platform.getCompatibleAppVariants().map { it.codename }
    val entryPlatform = platforms.firstOrNull { it.name in compatiblePlatforms } ?: return null
    val appVersionMatch = APP_VERSION_REGEX.find(version)
    val appVersionMajor = appVersionMatch?.groupValues?.getOrNull(1) ?: return null
    val appVersionMinor = appVersionMatch.groupValues.getOrNull(2) ?: return null
    val sdkVersionMatch = APP_VERSION_REGEX.find(entryPlatform.sdkVersion)
    val sdkVersionMajor = sdkVersionMatch?.groupValues?.getOrNull(1) ?: return null
    val sdkVersionMinor = sdkVersionMatch.groupValues.getOrNull(2) ?: return null
    return AppMetadata(
        uuid = id,
        flags = entryPlatform.processInfoFlags.toUInt(),
        icon = entryPlatform.pbwIconResourceId.toUInt(),
        appVersionMajor = appVersionMajor.toUByte(),
        appVersionMinor = appVersionMinor.toUByte(),
        sdkVersionMajor = sdkVersionMajor.toUByte(),
        sdkVersionMinor = sdkVersionMinor.toUByte(),
        appName = title
    )
}

data class LockerEntryAppstoreData(
    val hearts: Int,
    val developerId: String,
    val timelineEnabled: Boolean,
    val removeLink: String,
    val shareLink: String,
    val pbwLink: String,
    val userToken: String,
)

@Serializable
data class LockerEntryPlatform(
    val lockerEntryId: Uuid,
    val sdkVersion: String,
    val processInfoFlags: Int,
    val name: String,
    val screenshotImageUrl: String? = null,
    val listImageUrl: String? = null,
    val iconImageUrl: String? = null,
    val pbwIconResourceId: Int,
)

// Only some fields should trigger a watch resync if changed:
fun LockerEntryPlatform.recordHashCode(): Int {
    var result = sdkVersion.hashCode()
    result = 31 * result + processInfoFlags.hashCode()
    result = 31 * result + pbwIconResourceId
    return result
}

fun List<LockerEntryPlatform>.recordHashCode(): Int {
    var result = 0
    for (platform in this) {
        result = 31 * result + platform.recordHashCode()
    }
    return result
}

private
val APP_VERSION_REGEX = Regex("(\\d+)\\.(\\d+)(:?-.*)?")
