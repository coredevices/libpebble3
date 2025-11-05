package io.rebble.libpebblecommon.metadata

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform.CORE_ASTERIX
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform.CORE_OBELIX_BIGBOARD
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform.CORE_OBELIX_BIGBOARD_2
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform.CORE_OBELIX_DVT
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform.CORE_OBELIX_EVT
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

enum class WatchHardwarePlatform(val protocolNumber: UByte, val watchType: WatchType, val revision: String) {
    UNKNOWN(0u, WatchType.BASALT, "unknown"),
    PEBBLE_ONE_EV_1(1u, WatchType.APLITE, "ev1"),
    PEBBLE_ONE_EV_2(2u, WatchType.APLITE, "ev2"),
    PEBBLE_ONE_EV_2_3(3u, WatchType.APLITE, "ev2_3"),
    PEBBLE_ONE_EV_2_4(4u, WatchType.APLITE, "ev2_4"),
    PEBBLE_ONE_POINT_FIVE(5u, WatchType.APLITE, "v1_5"),
    PEBBLE_TWO_POINT_ZERO(6u, WatchType.APLITE, "v2_0"),
    PEBBLE_SNOWY_EVT_2(7u, WatchType.BASALT, "snowy_evt2"),
    PEBBLE_SNOWY_DVT(8u, WatchType.BASALT, "snowy_dvt"),
    PEBBLE_BOBBY_SMILES(10u, WatchType.BASALT, "snowy_s3"),
    PEBBLE_ONE_BIGBOARD_2(254u, WatchType.APLITE, "bb2"),
    PEBBLE_ONE_BIGBOARD(255u, WatchType.APLITE, "bigboard"),
    PEBBLE_SNOWY_BIGBOARD(253u, WatchType.BASALT, "snowy_bb2"),
    PEBBLE_SNOWY_BIGBOARD_2(252u, WatchType.BASALT, "unk"),
    PEBBLE_SPALDING_EVT(9u, WatchType.CHALK, "spalding_evt"),
    PEBBLE_SPALDING_PVT(11u, WatchType.CHALK, "spalding"),
    PEBBLE_SPALDING_BIGBOARD(251u, WatchType.CHALK, "spalding_bb2"),
    PEBBLE_SILK_EVT(12u, WatchType.DIORITE, "silk_evt"),
    PEBBLE_SILK(14u, WatchType.DIORITE, "silk"),
    CORE_ASTERIX(15u, WatchType.FLINT, "asterix"),
    CORE_OBELIX_EVT(16u, WatchType.EMERY, "obelix_evt"),
    CORE_OBELIX_DVT(17u, WatchType.EMERY, "obelix_dvt"),
    PEBBLE_SILK_BIGBOARD(250u, WatchType.DIORITE, "silk_bb"),
    PEBBLE_SILK_BIGBOARD_2_PLUS(248u, WatchType.DIORITE, "silk_bb2"),
    PEBBLE_ROBERT_EVT(13u, WatchType.EMERY, "robert_evt"),
    PEBBLE_ROBERT_BIGBOARD(249u, WatchType.EMERY, "robert_bb"),
    PEBBLE_ROBERT_BIGBOARD_2(247u, WatchType.EMERY, "robert_bb2"),
    CORE_OBELIX_BIGBOARD(244u, WatchType.EMERY, "obelix_bb"),
    CORE_OBELIX_BIGBOARD_2(243u, WatchType.EMERY, "obelix_bb2"),
    ;

    companion object {
        fun fromProtocolNumber(number: UByte): WatchHardwarePlatform {
            return entries.firstOrNull { it.protocolNumber == number } ?: UNKNOWN.also {
                Logger.d { "unknown hardware revision: $number" }
            }
        }

        fun fromHWRevision(revision: String?): WatchHardwarePlatform {
            if (revision == "unk") return UNKNOWN
            return entries.firstOrNull() { it.revision == revision } ?: UNKNOWN.also {
                Logger.d { "unknown hardware revision: $revision" }
            }
        }
    }
}

fun WatchHardwarePlatform.isCoreDevice(): Boolean = when (this) {
    CORE_ASTERIX, CORE_OBELIX_EVT, CORE_OBELIX_DVT, CORE_OBELIX_BIGBOARD, CORE_OBELIX_BIGBOARD_2 -> true
    else -> false
}

@Serializer(WatchHardwarePlatform::class)
class WatchHardwarePlatformSerializer {
    override val descriptor: SerialDescriptor
        get() = PrimitiveSerialDescriptor("WatchHardwarePlatform", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): WatchHardwarePlatform {
        val revision = decoder.decodeString()
        return WatchHardwarePlatform.fromHWRevision(revision) ?: throw SerializationException("Unknown hardware revision $revision")
    }

    override fun serialize(encoder: Encoder, value: WatchHardwarePlatform) {
        val revision = value.revision
        encoder.encodeString(revision)
    }

}