package io.rebble.libpebblecommon.database.entity

import androidx.compose.runtime.Immutable
import co.touchlab.kermit.Logger
import coredev.BlobDatabase
import coredev.GenerateRoomEntity
import io.rebble.libpebblecommon.database.dao.BlobDbItem
import io.rebble.libpebblecommon.metadata.WatchType
import io.rebble.libpebblecommon.packets.ProtocolCapsFlag
import io.rebble.libpebblecommon.packets.blobdb.TimelineAttribute
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem.Attribute
import io.rebble.libpebblecommon.services.blobdb.DbWrite
import io.rebble.libpebblecommon.structmapper.SFixedList
import io.rebble.libpebblecommon.structmapper.SFixedString
import io.rebble.libpebblecommon.structmapper.SNullTerminatedString
import io.rebble.libpebblecommon.structmapper.SShort
import io.rebble.libpebblecommon.structmapper.SUByte
import io.rebble.libpebblecommon.structmapper.StructMapper
import io.rebble.libpebblecommon.util.DataBuffer
import io.rebble.libpebblecommon.util.Endian
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

@Immutable
@GenerateRoomEntity(
    primaryKey = "id",
    databaseId = BlobDatabase.WatchPrefs,
    windowBeforeSecs = -1,
    windowAfterSecs = -1,
    onlyInsertAfter = false,
    sendDeletions = true,
)
data class WatchPrefItem(
    val id: String,
    val value: String,
) : BlobDbItem {
    override fun key(): UByteArray =
        SFixedString(StructMapper(), id.length, id).toBytes()

    override fun value(platform: WatchType, capabilities: Set<ProtocolCapsFlag>): UByteArray? {
        val type = WatchPref.from(id)
        if (type == null) {
            logger.w { "Don't know how to encode watch pref key: $id" }
            return null
        }
        logger.v { "trying to insert watch pref to watch blobdb: $id / $value" }
        val bytes = try {
            when (type.type) {
                WatchPrefType.TypeString -> SNullTerminatedString(StructMapper()).apply { set(value) }
                    .toBytes()

                WatchPrefType.TypeUuid -> SFixedString(StructMapper(), value.length).apply {
                    set(
                        value
                    )
                }.toBytes()

                WatchPrefType.TypeUint8 -> SUByte(StructMapper()).apply { set(value.toUByte()) }
                    .toBytes()

                WatchPrefType.TypeInt16 -> SShort(StructMapper()).apply { set(value.toShort()) }
                    .toBytes()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(e) { "Error encoding watch pref record $id" }
            return null
        }
        return bytes
    }

    override fun recordHashCode(): Int = hashCode()
}

enum class WatchPrefType {
    TypeString,
    TypeUuid,
    TypeUint8,
    TypeInt16,
}

enum class WatchPref(val id: String, val type: WatchPrefType) {
    //    Clock24h("clock24h", WatchPrefType.TypeString),
    TimezoneSource("timezoneSource", WatchPrefType.TypeUint8),
    AutomaticTimezoneID("automaticTimezoneID", WatchPrefType.TypeInt16),

    //    UnitsDistance("unitsDistance", WatchPrefType.TypeString),
    TextStyle("textStyle", WatchPrefType.TypeUint8),

    //    LightEnabled("lightEnabled", WatchPrefType.TypeString),
//    LightAmbientSensorEnabled("lightAmbientSensorEnabled", WatchPrefType.TypeString),
    LightTimeoutMs("lightTimeoutMs", WatchPrefType.TypeUint8),
    LightIntensity("lightIntensity", WatchPrefType.TypeUint8),

    //    LightMotion("lightMotion", WatchPrefType.TypeString),
    LightAmbientThreshold("lightAmbientThreshold", WatchPrefType.TypeUint8),

    //    LangEnglish("langEnglish", WatchPrefType.TypeString), // FIXME
    Watchface("watchface", WatchPrefType.TypeUuid),
    QlUp("qlUp", WatchPrefType.TypeUuid),
    QlDown("qlDown", WatchPrefType.TypeUuid),
    QlSelect("qlSelect", WatchPrefType.TypeUuid),
    QlBack("qlBack", WatchPrefType.TypeUuid),

    //    QlSetupOpened("qlSetupOpened", WatchPrefType.TypeString),
    // We set this separately using WatchSettingsDb - don't use it here
//    ActivityPreferences("activityPreferences", WatchPrefType.TypeString),
//    ActivityHealthAppOpened("activityHealthAppOpened", WatchPrefType.TypeString),
    WorkerId("workerId", WatchPrefType.TypeUuid),
    ;

    companion object {
        fun from(id: String): WatchPref? = entries.find { it.id == id }
    }
}

private val logger = Logger.withTag("NotificationAppItem")

fun DbWrite.asWatchPrefItem(): WatchPrefItem? {
    try {
        val id = key.asByteArray().decodeToString().trimEnd('\u0000')
        val type = WatchPref.from(id)
        if (type == null) {
            logger.w("Unknown watch pref type from blobdb: $id")
            return null
        }
        val strValue = try {
            when (type.type) {
                WatchPrefType.TypeString -> value.asByteArray().decodeToString().trimEnd('\u0000')
                WatchPrefType.TypeUuid -> Uuid.fromByteArray(value.asByteArray()).toString()
                WatchPrefType.TypeUint8 -> value.asByteArray()[0].toUInt().toString()
                WatchPrefType.TypeInt16 -> SShort(
                    StructMapper(),
                    endianness = Endian.Little
                ).apply {
                    fromBytes(
                        DataBuffer(value)
                    )
                }.get().toString()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(e) { "Error decoding watch pref record $id" }
            return null
        }
        return WatchPrefItem(
            id = id,
            value = strValue,
        )
    } catch (e: Exception) {
        logger.d("decoding watch pref record ${e.message}", e)
        return null
    }
}

private fun SFixedList<Attribute>.get(attribute: TimelineAttribute): UByteArray? =
    list.find { it.attributeId.get() == attribute.id }?.content?.get()
