package io.rebble.libpebblecommon.connection

import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.ImageBitmap
import io.rebble.libpebblecommon.connection.bt.ble.pebble.PebbleLeScanRecord
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdater
import io.rebble.libpebblecommon.connection.endpointmanager.musiccontrol.MusicTrack
import io.rebble.libpebblecommon.js.PKJSApp
import io.rebble.libpebblecommon.metadata.WatchColor
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
import kotlinx.io.files.Path
import kotlin.time.Instant
import kotlin.uuid.Uuid


interface ActiveDevice {
    fun disconnect()
    val usingBtClassic: Boolean
}

data class ConnectionFailureInfo(
    val reason: ConnectionFailureReason,
    val times: Int,
)

// <T : Transport> ?
@Stable
sealed interface PebbleDevice {
    val identifier: PebbleIdentifier
    val name: String
    val nickname: String?

    /**
     * Information about previous connection failures (until successfully connected).
     */
    val connectionFailureInfo: ConnectionFailureInfo?

    fun connect()
    fun displayName(): String {
        val actualNickname = nickname
        if (actualNickname != null && actualNickname.isNotEmpty()) {
            return actualNickname
        } else {
            return name
        }
    }
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
    val color: WatchColor?
    fun forget()
    fun setNickname(nickname: String?)
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
    ConnectedPebble.Battery,
    ConnectedPebble.DevConnection

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
    ConnectedPebble.CompanionAppControl,
    ConnectedPebble.Screenshot,
    ConnectedPebble.Language

/**
 * Put all specific functionality here, rather than directly in [ConnectedPebbleDevice].
 *
 * Eventually, implementations of these interfaces should all be what we're currently calling
 * "Endpoint Managers". For now, "Services" are OK.
 */
object ConnectedPebble {
    interface AppMessages {
        val transactionSequence: Iterator<UByte>
        suspend fun sendAppMessage(appMessageData: AppMessageData): AppMessageResult
        suspend fun sendAppMessageResult(appMessageResult: AppMessageResult)
        fun inboundAppMessages(appUuid: Uuid): Flow<AppMessageData>
    }

    interface Debug {
        suspend fun sendPing(cookie: UInt): UInt
        fun resetIntoPrf()
        fun createCoreDump()
    }

    interface DevConnection {
        suspend fun startDevConnection()
        suspend fun stopDevConnection()
        val devConnectionActive: StateFlow<Boolean>
    }

    interface Screenshot {
        suspend fun takeScreenshot(): ImageBitmap?
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
        fun updateFirmware(update: FirmwareUpdateCheckResult.FoundUpdate)
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
        suspend fun stopApp(uuid: Uuid)
        val runningApp: StateFlow<Uuid?>
    }

    interface PKJS {
        @Deprecated("Use more generic currentCompanionAppSession instead and cast if necessary")
        val currentPKJSSession: StateFlow<PKJSApp?>
    }

    interface CompanionAppControl {
        val currentCompanionAppSession: StateFlow<CompanionApp?>
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

    interface Language {
        suspend fun installLanguagePack(path: Path): Boolean
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
        val companionAppControl: CompanionAppControl,
        val devConnection: DevConnection,
        val screenshot: Screenshot,
        val language: Language,
    )

    class PrfServices(
        val firmware: FirmwareUpdater,
        val logs: Logs,
        val coreDump: CoreDump,
        val devConnection: DevConnection
    )
}
