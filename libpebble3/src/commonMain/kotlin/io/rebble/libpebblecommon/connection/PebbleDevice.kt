package io.rebble.libpebblecommon.connection

import androidx.compose.runtime.Stable
import io.rebble.libpebblecommon.connection.bt.ble.pebble.PebbleLeScanRecord
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdater
import io.rebble.libpebblecommon.connection.endpointmanager.musiccontrol.MusicTrack
import io.rebble.libpebblecommon.js.PKJSApp
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import io.rebble.libpebblecommon.music.MusicAction
import io.rebble.libpebblecommon.music.PlaybackState
import io.rebble.libpebblecommon.music.RepeatType
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import io.rebble.libpebblecommon.services.WatchInfo
import io.rebble.libpebblecommon.services.appmessage.AppMessageData
import io.rebble.libpebblecommon.services.appmessage.AppMessageResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.Instant
import kotlinx.io.files.Path
import kotlin.uuid.Uuid


interface ActiveDevice {
    fun disconnect()
}

// <T : Transport> ?
@Stable
sealed interface PebbleDevice {
    val transport: Transport
    val name: String get() = transport.name

    fun connect(uiContext: UIContext?)
}

interface DiscoveredPebbleDevice : PebbleDevice

// We know a few more things about these, after a BLE scan but before connection
interface BleDiscoveredPebbleDevice : DiscoveredPebbleDevice {
    val pebbleScanRecord: PebbleLeScanRecord
    val rssi: Int
}

// e.g. we have previously connected to it, and got all it's info (stored in the db)
interface KnownPebbleDevice : PebbleDevice {
    val runningFwVersion: String
    val serial: String
    val lastConnected: Instant
    val watchType: WatchHardwarePlatform
    fun forget()
}

interface DisconnectingPebbleDevice : PebbleDevice
interface DisconnectingKnownPebbleDevice : DisconnectingPebbleDevice, KnownPebbleDevice

interface ConnectingPebbleDevice : PebbleDevice, ActiveDevice {
    val negotiating: Boolean
    val rebootingAfterFirmwareUpdate: Boolean
}
interface ConnectingKnownPebbleDevice : ConnectingPebbleDevice, KnownPebbleDevice

interface ConnectedWatchInfo {
    val watchInfo: WatchInfo
}

interface CommonConnectedDevice : KnownPebbleDevice,
    ActiveDevice,
    ConnectedPebble.Firmware,
    ConnectedWatchInfo,
    ConnectedPebble.Logs,
    ConnectedPebble.CoreDump,
    ConnectedPebble.Battery

interface ConnectedPebbleDeviceInRecovery : CommonConnectedDevice

sealed interface ConnectedPebbleDevice :
    CommonConnectedDevice,
    KnownPebbleDevice,
    ConnectedPebble.Debug,
    ConnectedPebble.Messages,
    ConnectedPebble.AppRunState,
    ConnectedPebble.Time,
    ConnectedPebble.AppMessages,
    ConnectedPebble.Music,
    ConnectedPebble.PKJS,
    ConnectedPebble.DevConnection

/**
 * Put all specific functionality here, rather than directly in [ConnectedPebbleDevice].
 *
 * Eventually, implementations of these interfaces should all be what we're currently calling
 * "Endpoint Managers". For now, "Services" are OK.
 */
object ConnectedPebble {
    interface AppMessages {
        val inboundAppMessages: Flow<AppMessageData>
        val transactionSequence: Iterator<Byte>
        suspend fun sendAppMessage(appMessageData: AppMessageData): AppMessageResult
        suspend fun sendAppMessageResult(appMessageResult: AppMessageResult)
    }

    interface Debug {
        suspend fun sendPing(cookie: UInt): UInt
        suspend fun resetIntoPrf()
    }

    interface DevConnection {
        suspend fun startDevConnection()
        suspend fun stopDevConnection()
        val devConnectionActive: StateFlow<Boolean>
    }

    interface Logs {
        suspend fun gatherLogs(): Path?
    }

    interface Messages {
        suspend fun sendPPMessage(bytes: ByteArray)
        suspend fun sendPPMessage(ppMessage: PebblePacket)
        val inboundMessages: Flow<PebblePacket>
        val rawInboundMessages: Flow<ByteArray>
    }

    interface FirmwareUpdate {
        fun sideloadFirmware(path: Path)
        fun updateFirmware(update: FirmwareUpdateCheckResult)
        fun checkforFirmwareUpdate()
    }

    interface FirmwareStatus {
        val firmwareUpdateState: FirmwareUpdater.FirmwareUpdateStatus
        val firmwareUpdateAvailable: FirmwareUpdateCheckResult?
    }

    interface Firmware : FirmwareUpdate, FirmwareStatus

    interface Battery {
        val batteryLevel: Int?
    }

    interface AppRunState {
        suspend fun launchApp(uuid: Uuid)
        val runningApp: StateFlow<Uuid?>
    }

    interface PKJS {
        val currentPKJSSession: StateFlow<PKJSApp?>
    }

    interface Time {
        suspend fun updateTime()
    }

    interface CoreDump {
        suspend fun getCoreDump(unread: Boolean): Path?
    }

    interface Music {
        suspend fun updateTrack(track: MusicTrack)
        suspend fun updatePlaybackState(
            state: PlaybackState,
            trackPosMs: UInt,
            playbackRatePct: UInt,
            shuffle: Boolean,
            repeatType: RepeatType
        )
        suspend fun updatePlayerInfo(packageId: String, name: String)
        suspend fun updateVolumeInfo(volumePercent: UByte)
        val musicActions: Flow<MusicAction>
        val updateRequestTrigger: Flow<Unit>
    }

    class Services(
        val debug: Debug,
        val appRunState: AppRunState,
        val firmware: FirmwareUpdater,
        val messages: Messages,
        val time: Time,
        val appMessages: AppMessages,
        val logs: Logs,
        val coreDump: CoreDump,
        val music: Music,
        val pkjs: PKJS,
        val devConnection: DevConnection,
    )

    class PrfServices(
        val firmware: FirmwareUpdater,
        val logs: Logs,
        val coreDump: CoreDump,
    )
}
