package io.rebble.libpebblecommon.packets

import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import io.rebble.libpebblecommon.packets.PhoneAppVersion.AppVersionRequest
import io.rebble.libpebblecommon.packets.PhoneAppVersion.AppVersionResponse
import io.rebble.libpebblecommon.packets.WatchVersion.WatchVersionRequest
import io.rebble.libpebblecommon.packets.WatchVersion.WatchVersionResponse
import io.rebble.libpebblecommon.protocolhelpers.PacketRegistry
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import io.rebble.libpebblecommon.protocolhelpers.ProtocolEndpoint
import io.rebble.libpebblecommon.structmapper.SBoolean
import io.rebble.libpebblecommon.structmapper.SBytes
import io.rebble.libpebblecommon.structmapper.SFixedString
import io.rebble.libpebblecommon.structmapper.SOptional
import io.rebble.libpebblecommon.structmapper.SShort
import io.rebble.libpebblecommon.structmapper.SString
import io.rebble.libpebblecommon.structmapper.SUByte
import io.rebble.libpebblecommon.structmapper.SUInt
import io.rebble.libpebblecommon.structmapper.SUShort
import io.rebble.libpebblecommon.structmapper.StructMappable
import io.rebble.libpebblecommon.structmapper.StructMapper
import io.rebble.libpebblecommon.util.Endian

sealed class SystemPacket(endpoint: ProtocolEndpoint) : PebblePacket(endpoint)

open class TimeMessage(message: Message) : SystemPacket(
    ProtocolEndpoint.TIME
) {
    enum class Message(val value: UByte) {
        GetTimeRequest(0x00u),
        GetTimeResponse(0x01u),
        SetLocalTime(0x02u),
        SetUTC(0x03u)
    }

    val command = SUByte(m, message.value)

    init {
        type = command.get()
    }

    class GetTimeRequest : TimeMessage(Message.GetTimeRequest)
    class GetTimeResponse(time: UInt = 0u) : TimeMessage(Message.GetTimeResponse) {
        val time = SUInt(m, time)
    }

    class SetLocalTime(time: UInt = 0u) : TimeMessage(Message.SetLocalTime) {
        val time = SUInt(m, time)
    }

    class SetUTC(unixTime: UInt = 0u, utcOffset: Short = 0, timeZoneName: String = "") :
        TimeMessage(
            Message.SetUTC
        ) {
        val unixTime = SUInt(m, unixTime)
        val utcOffset = SShort(m, utcOffset)

        val timeZoneName = SString(m, timeZoneName)
    }
}

open class PhoneAppVersion(message: Message) : SystemPacket(endpoint) {
    enum class Message(val value: UByte) {
        AppVersionRequest(0x00u),
        AppVersionResponse(0x01u)
    }

    val command = SUByte(m, message.value)

    init {
        type = command.get()
    }

    enum class OSType(val value: UInt) {
        Unknown(0u),
        IOS(1u),
        Android(2u),
        MacOS(3u),
        Linux(4u),
        Windows(5u);

        companion object {
            fun fromValue(value: UInt): OSType {
                return values().firstOrNull { it.value == value }
                    ?: error("Unknown OS type value: $value")
            }
        }
    }

    enum class SessionCapsFlag(val value: UByte) {
        Geolocation(1u),
        GammaRay(Int.MIN_VALUE.toUByte())
    }

    enum class PlatformFlag(val value: UInt) {
        Telephony(16u),
        SMS(32u),
        GPS(64u),
        BTLE(128u),

        // This value is not multiple of 2 and cannot be uniquely (de)serialized as flags.
        // Bug in original Pebble app?
        // CameraFront(240u),
        CameraRear(256u),
        Accelerometer(512u),
        Gyroscope(1024u),
        Compass(2048u);

        companion object {
            fun makeFlags(osType: OSType, flags: List<PlatformFlag>): UInt {
                var ret: UInt = osType.value
                flags.forEach { flag ->
                    ret = ret or flag.value
                }
                return ret
            }

            fun fromFlags(combinedFlags: UInt): Pair<OSType, List<PlatformFlag>> {
                val osType = OSType.fromValue(combinedFlags and 15u)
                val flags = values().filter { (combinedFlags and it.value) != 0u }

                return osType to flags
            }
        }
    }

    class AppVersionRequest : PhoneAppVersion(Message.AppVersionRequest)
    class AppVersionResponse() : PhoneAppVersion(Message.AppVersionResponse) {
        /**
         * Protocol version. Pebble app always sends [UInt.MAX_VALUE] here.
         *
         * Unused as of v3.0
         */
        val protocolVersion = SUInt(m)

        /**
         * [SessionCapsFlag] flags. Purpose unknown.
         *
         * Note that Pebble app always sends 0 here (no flags)
         *
         * Unused as of v3.0
         */
        val sessionCaps = SUInt(m)

        /**
         * Combined flag for [PlatformFlag] (list of supported features of the phone) and
         * [OSType].
         *
         * Use [PlatformFlag.makeFlags] to serialize and [PlatformFlag.fromFlags] to
         * deserialize.
         *
         * Note [PlatformFlag] part seems to be left unused in the Pebble app.
         */
        val platformFlags = SUInt(m)

        /**
         * Unknown (Pebble app always sends 2 here)
         */
        val responseVersion = SUByte(m, 2u)

        /**
         * Major version of the phone app.
         *
         * Last Pebble app version was 4.4.2, so using "4" here would probably
         * result in a best compatibility with the original Pebble firmware
         */
        val majorVersion = SUByte(m)

        /**
         * Minor version of the phone app.
         *
         * Last Pebble app version was 4.4.2, so using "4" here would probably
         * result in a best compatibility with the original Pebble firmware
         */
        val minorVersion = SUByte(m)

        /**
         * Bugfix version of the phone app.
         *
         * Last Pebble app version was 4.4.2, so using "2" here would probably
         * result in a best compatibility with the original Pebble firmware
         */
        val bugfixVersion = SUByte(m)

        /**
         * Flags for supported protocol features.
         *
         * Use [ProtocolCapsFlag.makeFlags] to serialize and [ProtocolCapsFlag.fromFlags] to
         * deserialize.
         */
        val protocolCaps = SBytes(m, 8)

        constructor(
            protocolVersion: UInt,
            sessionCaps: UInt,
            platformFlags: UInt,
            responseVersion: UByte,
            majorVersion: UByte,
            minorVersion: UByte,
            bugfixVersion: UByte,
            protocolCaps: UByteArray
        ) : this() {
            this.protocolVersion.set(protocolVersion)
            this.sessionCaps.set(sessionCaps)
            this.platformFlags.set(platformFlags)
            this.responseVersion.set(responseVersion)
            this.majorVersion.set(majorVersion)
            this.minorVersion.set(minorVersion)
            this.bugfixVersion.set(bugfixVersion)
            this.protocolCaps.set(protocolCaps)
        }
    }

    companion object {
        val endpoint = ProtocolEndpoint.PHONE_VERSION
    }
}

open class WatchVersion(message: Message) : SystemPacket(endpoint) {
    enum class Message(val value: UByte) {
        WatchVersionRequest(0x00u),
        WatchVersionResponse(0x01u)
    }

    val command = SUByte(m, message.value)

    init {
        type = command.get()
    }

    class WatchVersionRequest : WatchVersion(Message.WatchVersionRequest)
    class WatchVersionResponse() : WatchVersion(Message.WatchVersionResponse) {
        /**
         * Version of the running firmware
         */
        val running = WatchFirmwareVersion().also { m.register(it) }

        /**
         * Version of the recovery firmware
         */
        val recovery = WatchFirmwareVersion().also { m.register(it) }

        /**
         * Likely the timestamp of the bootloader build
         */
        val bootloaderTimestamp = SUInt(m)

        /**
         * Board name
         */
        val board = SFixedString(m, 9)

        /**
         * Serial number of the watch
         */
        val serial = SFixedString(m, 12)

        /**
         * Bluetooth MAC address of the watch
         */
        val btAddress = SBytes(m, 6)

        /**
         * ???
         */
        val resourceCrc = SUInt(m)

        /**
         * ???
         */
        val resourceTimestamp = SUInt(m)

        // TODO everything after this is only set after fw version x, but the buffer might be
        //  present + <undefined> before that

        /**
         * Currently selected language on the watch
         */
        val language = SFixedString(m, 6)

        /**
         * Version of the currently selected language
         */
        val languageVersion = SUShort(m)

        /**
         * Flags for supported protocol features.
         *
         * Use [ProtocolCapsFlag.makeFlags] to serialize and [ProtocolCapsFlag.fromFlags] to
         * deserialize.
         */
        val capabilities = SBytes(m, 8)

        /**
         * When *true*, it means watch has been connected to a different phone before this phone.
         *
         * Flag remains true until
         * [io.rebble.libpebblecommon.packets.blobdb.BlobCommand.ClearCommand] is
         * sent to the watch
         * Note: currently always present when serialized, but optional when deserialized
         */
        val isUnfaithful = SOptional(m, SBoolean(StructMapper()), true)

        val healthInsightsVersion = SOptional(m, SUShort(StructMapper()), true)

        val javascriptVersion = SOptional(m, SUShort(StructMapper()), true)
    }

    companion object {
        val endpoint = ProtocolEndpoint.WATCH_VERSION
    }
}

/**
 * Retreive various factory-embedded metadata from the watch.
 *
 * Current known keys:
 *
 * `mfg_color` - returns watch model number (UInt32)
 */
open class WatchFactoryData(message: Message) : SystemPacket(endpoint) {
    enum class Message(val value: UByte) {
        WatchModelRequest(0x00u),
        WatchModelResponse(0x01u),
        WatchModelError(0xffu)
    }

    val command = SUByte(m, message.value)

    init {
        type = command.get()
    }

    class WatchFactoryDataRequest(key: String) : WatchFactoryData(Message.WatchModelRequest) {
        val key = SString(m, key)
    }

    class WatchFactoryDataError : WatchFactoryData(Message.WatchModelError)
    class WatchFactoryDataResponse() : WatchFactoryData(Message.WatchModelResponse) {
        val length = SUByte(m)
        val model = SBytes(m, 0).apply { linkWithSize(length) }
    }

    companion object {
        val endpoint = ProtocolEndpoint.FCT_REG
    }
}

enum class ProtocolCapsFlag(val value: Int) {
    SupportsAppRunStateProtocol(0),
    SupportsInfiniteLogDump(1),
    SupportsExtendedMusicProtocol(2),
    SupportsTwoWayDismissal(3),
    SupportsLocalization(4),
    Supports8kAppMessage(5),
    SupportsHealthInsights(6),
    SupportsAppDictation(7),
    SupportsSendTextApp(8),
    SupportsNotificationFiltering(9),
    SupportsUnreadCoreDump(10),
    SupportsWeatherApp(11),
    SupportsRemindersApp(12),
    SupportsWorkoutApp(13),
    SupportsSmoothFwInstallProgress(14),
    JavascriptBytecodeVersionAppended(16),
    SupportsFwUpdateAcrossDisconnection(21),
    ;

    companion object {
        fun makeFlags(flags: List<ProtocolCapsFlag>): UByteArray {
            val bytes = UByteArray(8)

            for (flag in flags) {
                val combinedPosition = flag.value
                val byteIndex: Int = combinedPosition / 8
                val positionInsideByte: Int = combinedPosition % 8
                bytes[byteIndex] = (1u shl positionInsideByte).toUByte() or bytes[byteIndex]
            }

            return bytes
        }

        fun fromFlags(flags: UByteArray): Set<ProtocolCapsFlag> {
            return values().filter {
                val combinedPosition = it.value
                val byteIndex: Int = combinedPosition / 8
                val positionInsideByte: Int = combinedPosition % 8
                ((1u shl positionInsideByte) and flags[byteIndex].toUInt()) != 0u
            }.toSet()
        }
    }
}

enum class FirmwareProperty(val value: Int) {
    IsRecoveryFirmware(0),
    IsBleFirmware(1),
    IsDualSlot(2),
    IsSlot0(3),
    ;

    companion object {
        fun fromFlags(flags: UByte): Set<FirmwareProperty> {
            return entries.filter {
                val positionInsideByte: Int = it.value
                ((1u shl positionInsideByte) and flags.toUInt()) != 0u
            }.toSet()
        }
    }
}

class WatchFirmwareVersion : StructMappable() {
    val timestamp = SUInt(m)
    val versionTag = SFixedString(m, 32)
    val gitHash = SFixedString(m, 8)
    val flags = SUByte(m)

    /**
     * See [WatchHardwarePlatform]
     */
    val hardwarePlatform = SUByte(m)
    val metadataVersion = SUByte(m)
}

open class SystemMessage(message: Message) : SystemPacket(endpoint) {
    enum class Message(val value: UByte) {
        NewFirmwareAvailable(0x00u),
        FirmwareUpdateStart(0x01u),
        FirmwareUpdateComplete(0x02u),
        FirmwareUpdateFailed(0x03u),
        FirmwareUpToDate(0x04u),
        StopReconnecting(0x06u),
        StartReconnecting(0x07u),
        MAPDisabled(0x08u),
        MAPEnabled(0x09u),
        FirmwareUpdateStartResponse(0x0au)
    }

    val command = SUByte(m, 0x0u)
    val messageType = SUByte(m, message.value)

    init {
        type = messageType.get()
    }

    companion object {
        val endpoint = ProtocolEndpoint.SYSTEM_MESSAGE
    }

    class NewFirmwareAvailable: SystemMessage(Message.NewFirmwareAvailable)
    class FirmwareUpdateStart(bytesAlreadyTransferred: UInt, bytesToSend: UInt): SystemMessage(Message.FirmwareUpdateStart) {
        val bytesAlreadyTransferred = SUInt(m, bytesAlreadyTransferred, endianness = Endian.Little)
        val bytesToSend = SUInt(m, bytesToSend, endianness = Endian.Little)
    }
    class FirmwareUpdateComplete: SystemMessage(Message.FirmwareUpdateComplete)
    class FirmwareUpdateFailed: SystemMessage(Message.FirmwareUpdateFailed)
    class FirmwareUpToDate: SystemMessage(Message.FirmwareUpToDate)
    class StopReconnecting: SystemMessage(Message.StopReconnecting)
    class StartReconnecting: SystemMessage(Message.StartReconnecting)
    class MAPDisabled: SystemMessage(Message.MAPDisabled)
    class MAPEnabled: SystemMessage(Message.MAPEnabled)
    class FirmwareUpdateStartResponse: SystemMessage(Message.FirmwareUpdateStartResponse) {
        val response = SUByte(m)
    }

    enum class FirmwareUpdateStartStatus(val value: UByte) {
        Stopped(0x00u),
        Started(0x01u),
        Cancelled(0x02u);

        companion object {
            fun fromValue(value: UByte): FirmwareUpdateStartStatus {
                return entries.firstOrNull { it.value == value }
                    ?: error("Unknown firmware update start status value: $value")
            }
        }
    }
}

class BLEControl(opcode: UByte = 0x4u, discoverable: Boolean, duration: UShort) : SystemPacket(
    endpoint
) {
    val command = SUByte(m, opcode)

    //val discoverable = SBool(m, discoverable)
    val duration = SUShort(m, duration)

    init {
        type = command.get()
        TODO("Incomplete packet declaration")
    }

    companion object {
        val endpoint = ProtocolEndpoint.BLE_CONTROL
    }
}

open class PingPong(message: Message, cookie: UInt) : SystemPacket(endpoint) {
    enum class Message(val value: UByte) {
        Ping(0u),
        Pong(1u)
    }

    val command = SUByte(m, message.value)
    val cookie = SUInt(m, cookie)

    init {
        type = command.get()
    }

    class Ping(cookie: UInt = 0u) : PingPong(Message.Ping, cookie) {
    }

    class Pong(cookie: UInt = 0u) : PingPong(Message.Pong, cookie) {
    }

    companion object {
        val endpoint = ProtocolEndpoint.PING
    }
}

fun timePacketsRegister() {
    PacketRegistry.register(ProtocolEndpoint.TIME, TimeMessage.Message.SetUTC.value) {
        TimeMessage.SetUTC()
    }
}

fun systemPacketsRegister() {
    PacketRegistry.register(
        PhoneAppVersion.endpoint,
        PhoneAppVersion.Message.AppVersionRequest.value
    ) { AppVersionRequest() }
    PacketRegistry.register(
        PhoneAppVersion.endpoint,
        PhoneAppVersion.Message.AppVersionResponse.value
    ) { AppVersionResponse() }

    PacketRegistry.register(
        WatchVersion.endpoint,
        WatchVersion.Message.WatchVersionRequest.value
    ) { WatchVersionRequest() }
    PacketRegistry.register(
        WatchVersion.endpoint,
        WatchVersion.Message.WatchVersionResponse.value
    ) { WatchVersionResponse() }

    PacketRegistry.register(
        WatchFactoryData.endpoint,
        WatchFactoryData.Message.WatchModelRequest.value
    ) { WatchFactoryData.WatchFactoryDataRequest("") }
    PacketRegistry.register(
        WatchFactoryData.endpoint,
        WatchFactoryData.Message.WatchModelResponse.value
    ) { WatchFactoryData.WatchFactoryDataResponse() }
    PacketRegistry.register(
        WatchFactoryData.endpoint,
        WatchFactoryData.Message.WatchModelError.value
    ) { WatchFactoryData.WatchFactoryDataError() }

    PacketRegistry.register(PingPong.endpoint, PingPong.Message.Ping.value) { PingPong.Ping() }
    PacketRegistry.register(PingPong.endpoint, PingPong.Message.Pong.value) { PingPong.Pong() }

    PacketRegistry.registerCustomTypeOffset(SystemMessage.endpoint, 5) // command is always 0, type is next byte
    PacketRegistry.register(
        SystemMessage.endpoint,
        SystemMessage.Message.FirmwareUpdateStartResponse.value
    ) { SystemMessage.FirmwareUpdateStartResponse() }
}