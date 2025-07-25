package io.rebble.libpebblecommon.connection

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.calls.Call
import io.rebble.libpebblecommon.connection.bt.BluetoothState
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdater
import io.rebble.libpebblecommon.connection.endpointmanager.musiccontrol.MusicTrack
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.CustomTimelineActionHandler
import io.rebble.libpebblecommon.database.asMillisecond
import io.rebble.libpebblecommon.database.dao.AppWithCount
import io.rebble.libpebblecommon.database.dao.ChannelAndCount
import io.rebble.libpebblecommon.database.entity.CalendarEntity
import io.rebble.libpebblecommon.database.entity.ChannelGroup
import io.rebble.libpebblecommon.database.entity.ChannelItem
import io.rebble.libpebblecommon.database.entity.MuteState
import io.rebble.libpebblecommon.database.entity.NotificationAppItem
import io.rebble.libpebblecommon.database.entity.NotificationEntity
import io.rebble.libpebblecommon.database.entity.TimelineNotification
import io.rebble.libpebblecommon.js.PKJSApp
import io.rebble.libpebblecommon.locker.AppPlatform
import io.rebble.libpebblecommon.locker.AppProperties
import io.rebble.libpebblecommon.locker.AppType
import io.rebble.libpebblecommon.locker.LockerWrapper
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import io.rebble.libpebblecommon.metadata.WatchType
import io.rebble.libpebblecommon.music.MusicAction
import io.rebble.libpebblecommon.music.PlaybackState
import io.rebble.libpebblecommon.music.RepeatType
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.services.WatchInfo
import io.rebble.libpebblecommon.services.appmessage.AppMessageData
import io.rebble.libpebblecommon.services.appmessage.AppMessageResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.Instant
import kotlinx.io.files.Path
import kotlin.random.Random
import kotlin.uuid.Uuid

class FakeLibPebble : LibPebble {
    override fun init() {
        // No-op
    }

    override val watches: PebbleDevices = MutableStateFlow(fakeWatches())
    override val connectionEvents: Flow<PebbleConnectionEvent> = MutableSharedFlow()

    override fun watchesDebugState(): String {
        return ""
    }

    override val config: StateFlow<LibPebbleConfig> = MutableStateFlow(LibPebbleConfig())

    override fun updateConfig(config: LibPebbleConfig) {
        // No-op
    }

    override suspend fun sendNotification(
        notification: TimelineNotification,
        actionHandlers: Map<UByte, CustomTimelineActionHandler>?
    ) {
        // No-op
    }

    override suspend fun markNotificationRead(itemId: Uuid) {
        // No-op
    }

    override suspend fun sendPing(cookie: UInt) {
        // No-op
    }

    override suspend fun launchApp(uuid: Uuid) {
        // No-op
    }

    override fun doStuffAfterPermissionsGranted() {
        // No-op
    }

    override fun checkForFirmwareUpdates() {
    }

    // Scanning interface
    override val bluetoothEnabled: StateFlow<BluetoothState> =
        MutableStateFlow(BluetoothState.Enabled)

    override val isScanningBle: StateFlow<Boolean> = MutableStateFlow(false)

    override fun startBleScan() {
        // No-op
    }

    override fun stopBleScan() {
        // No-op
    }

    override fun startClassicScan() {
        // No-op
    }

    override fun stopClassicScan() {
        // No-op
    }

    // RequestSync interface
    override fun requestLockerSync(): Deferred<Unit> {
        return CompletableDeferred(Unit)
    }

    // LockerApi interface
    override suspend fun sideloadApp(pbwPath: Path): Boolean {
        // No-op
        return true
    }

    val locker = MutableStateFlow(fakeLockerEntries())

    override fun getLocker(): Flow<List<LockerWrapper>> {
        return locker
    }

    override suspend fun setAppOrder(id: Uuid, order: Int) {
        // No-op
    }

    val _notificationApps = MutableStateFlow(fakeNotificationApps())

    override val notificationApps: Flow<List<AppWithCount>> = _notificationApps.map { it.map { AppWithCount(it, 0) } }
    override fun notificationAppChannelCounts(packageName: String): Flow<List<ChannelAndCount>> =
        MutableStateFlow(emptyList())

    override fun mostRecentNotificationsFor(
        pkg: String,
        channelId: String?,
        limit: Int
    ): Flow<List<NotificationEntity>> = flow { emptyList<NotificationEntity>() }

    override fun updateNotificationAppMuteState(packageName: String, muteState: MuteState) {
        // No-op
    }

    override fun updateNotificationChannelMuteState(
        packageName: String,
        channelId: String,
        muteState: MuteState
    ) {
        // No-op
    }

    override suspend fun getAppIcon(packageName: String): ImageBitmap? {
        // Return a green square as a placeholder
        val width = 48
        val height = 48
        val buffer = IntArray(width * height) { Color.Green.toArgb() }
        return ImageBitmap(width, height).apply { readPixels(buffer) }
    }

    // CallManagement interface
    override val currentCall: MutableStateFlow<Call?> = MutableStateFlow(null)

    // Calendar interface
    override fun calendars(): Flow<List<CalendarEntity>> {
        return emptyFlow()
    }

    override fun updateCalendarEnabled(calendarId: Int, enabled: Boolean) {
        // No-op
    }

    // OtherPebbleApps interface
    override val otherPebbleCompanionAppsInstalled: StateFlow<List<OtherPebbleApp>> =
        MutableStateFlow(emptyList())

    override suspend fun getAccountToken(appUuid: Uuid): String? {
        return ""
    }
}

fun fakeWatches(): List<PebbleDevice> {
    return buildList {
        for (i in 1..8) {
            add(fakeWatch())
        }
    }
}

fun fakeWatch(): PebbleDevice {
    val num = Random.nextInt(1111, 9999)
    val connected = Random.nextBoolean()
    val fakeTransport = Transport.BluetoothTransport.BleTransport(
        identifier = randomMacAddress().asPebbleBluetoothIdentifier(),
        name = "Core $num",
    )
    return if (connected) {
        val updating = Random.nextBoolean()
        val fwupState = if (updating) {
            val fakeUpdate = FirmwareUpdateCheckResult(
                version = FirmwareVersion.from("v4.9.9-core1", isRecovery = false, gitHash = "", timestamp = kotlin.time.Instant.DISTANT_PAST)!!,
                url = "",
                notes = "v4.9.9-core1 is great",
            )
            FirmwareUpdater.FirmwareUpdateStatus.InProgress(fakeUpdate, MutableStateFlow(0.47f))
        } else {
            FirmwareUpdater.FirmwareUpdateStatus.NotInProgress.Idle
        }
        FakeConnectedDevice(
            transport = fakeTransport,
            firmwareUpdateAvailable = null,
            firmwareUpdateState = fwupState,
        )
    } else {
        object : DiscoveredPebbleDevice {
            override val transport: Transport = fakeTransport

            override fun connect(uiContext: UIContext?) {
            }
        }
    }
}

class FakeConnectedDevice(
    override val transport: Transport,
    override val firmwareUpdateAvailable: FirmwareUpdateCheckResult?,
    override val firmwareUpdateState: FirmwareUpdater.FirmwareUpdateStatus,
) : ConnectedPebbleDevice {
    override val runningFwVersion: String = "v1.2.3-core"
    override val serial: String = "XXXXXXXXXXXX"
    override val lastConnected: Instant = Instant.DISTANT_PAST
    override val watchType: WatchHardwarePlatform = WatchHardwarePlatform.CORE_ASTERIX

    override fun forget() {}

    override fun connect(uiContext: UIContext?) {}

    override fun disconnect() {}

    override suspend fun sendPing(cookie: UInt): UInt = cookie

    override suspend fun resetIntoPrf() {}

    override suspend fun sendPPMessage(bytes: ByteArray) {}

    override suspend fun sendPPMessage(ppMessage: PebblePacket) {}

    override val inboundMessages: Flow<PebblePacket> = MutableSharedFlow()
    override val rawInboundMessages: Flow<ByteArray> = MutableSharedFlow()

    override fun sideloadFirmware(path: Path) {}

    override fun updateFirmware(update: FirmwareUpdateCheckResult) {}

    override fun checkforFirmwareUpdate() {}

    override suspend fun launchApp(uuid: Uuid) {}

    override val runningApp: StateFlow<Uuid?> = MutableStateFlow(null)
    override val watchInfo: WatchInfo
        get() = TODO("Not yet implemented")

    override suspend fun updateTime() {}

    override val inboundAppMessages: Flow<AppMessageData> = MutableSharedFlow()
    override val transactionSequence: Iterator<Byte> = iterator { }

    override suspend fun sendAppMessage(appMessageData: AppMessageData): AppMessageResult =
        AppMessageResult.ACK(appMessageData.transactionId)

    override suspend fun sendAppMessageResult(appMessageResult: AppMessageResult) {}

    override suspend fun gatherLogs(): Path? = null

    override suspend fun getCoreDump(unread: Boolean): Path? = null

    override suspend fun updateTrack(track: MusicTrack) {}

    override suspend fun updatePlaybackState(
        state: PlaybackState,
        trackPosMs: UInt,
        playbackRatePct: UInt,
        shuffle: Boolean,
        repeatType: RepeatType
    ) {}

    override suspend fun updatePlayerInfo(packageId: String, name: String) {}

    override suspend fun updateVolumeInfo(volumePercent: UByte) {}

    override val musicActions: Flow<MusicAction> = MutableSharedFlow()
    override val updateRequestTrigger: Flow<Unit> = MutableSharedFlow()
    override val currentPKJSSession: StateFlow<PKJSApp?> = MutableStateFlow(null)

    override suspend fun startDevConnection() {}
    override suspend fun stopDevConnection() {}
    override val devConnectionActive: StateFlow<Boolean> = MutableStateFlow(false)
    override val batteryLevel: Int? = 50
}

fun fakeNotificationApps(): List<NotificationAppItem> {
    return buildList {
        for (i in 1..50) {
            add(fakeNotificationApp())
        }
    }
}

fun fakeNotificationApp(): NotificationAppItem {
    return NotificationAppItem(
        name = randomName(),
        packageName = randomName(),
        muteState = if (Random.nextBoolean()) MuteState.Always else MuteState.Never,
        channelGroups = if (Random.nextBoolean()) emptyList() else fakeChannelGroups(),
        stateUpdated = Instant.DISTANT_PAST.asMillisecond(),
        lastNotified = Instant.DISTANT_PAST.asMillisecond(),
    )
}

fun fakeChannelGroups(): List<ChannelGroup> {
    return buildList {
        for (i in 1..Random.nextInt(2,5)) {
            add(ChannelGroup(
                id = randomName(),
                name = randomName(),
                channels = fakeChannels(),
            ))
        }
    }
}

fun fakeChannels(): List<ChannelItem> {
    return buildList {
        for (i in 1..Random.nextInt(1, 8)) {
            add(
                ChannelItem(
                    id = randomName(),
                    name = randomName(),
                    muteState = if (Random.nextBoolean()) MuteState.Always else MuteState.Never,
                )
            )
        }
    }
}

fun fakeLockerEntries(): List<LockerWrapper> {
    return buildList {
        for (i in 1..40) {
            add(fakeLockerEntry())
        }
    }
}

fun randomName(): String {
    val length = Random.nextInt(5, 20)
    val allowedChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    return (1..length)
        .map { allowedChars[Random.nextInt(0, allowedChars.length)] }
        .joinToString("")
}

fun randomMacAddress(): String {
    val allowedChars = "0123456789ABCDEF"
    return (1..6).joinToString(":") {
        (1..2).map {
            allowedChars[Random.nextInt(
                0,
                allowedChars.length
            )]
        }.joinToString("")
    }
}

fun fakeLockerEntry(): LockerWrapper {
    val appType = if (Random.nextBoolean()) AppType.Watchface else AppType.Watchapp
    return LockerWrapper.NormalApp(
        properties = AppProperties(
            id = Uuid.random(),
            type = appType,
            title = randomName(),
            developerName = "Core Devices",
            platforms = listOf(
                AppPlatform(
                    watchType = WatchType.CHALK,
                    screenshotImageUrl = "https://assets2.rebble.io/180x180/ZiFWSDWHTwearl6RNBNA",
                    listImageUrl = "https://assets2.rebble.io/exact/180x180/LVK5AGVeS1ufpR8NNk7C",
                    iconImageUrl = "",
                ),
                AppPlatform(
                    watchType = WatchType.DIORITE,
                    screenshotImageUrl = "https://assets2.rebble.io/144x168/u8q7BQv0QjGkLXy4WydA",
                    listImageUrl = "https://assets2.rebble.io/exact/144x168/LVK5AGVeS1ufpR8NNk7C",
                    iconImageUrl = "",
                ), AppPlatform(
                    watchType = WatchType.BASALT,
                    screenshotImageUrl = "https://assets2.rebble.io/144x168/LVK5AGVeS1ufpR8NNk7C",
                    listImageUrl = "https://assets2.rebble.io/exact/144x168/LVK5AGVeS1ufpR8NNk7C",
                    iconImageUrl = "",
                ),
                AppPlatform(
                    watchType = WatchType.APLITE,
                    screenshotImageUrl = "https://assets2.rebble.io/144x168/7fNxWcZ3RZ2clRNWA68Q",
                    listImageUrl = "https://assets2.rebble.io/exact/144x168/LVK5AGVeS1ufpR8NNk7C",
                    iconImageUrl = "",
                )
            ),
        ),
        sideloaded = false,
        configurable = Random.nextBoolean(),
        sync = true,
    )
}
