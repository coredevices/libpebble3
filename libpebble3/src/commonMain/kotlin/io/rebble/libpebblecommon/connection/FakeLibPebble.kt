package io.rebble.libpebblecommon.connection

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import io.ktor.util.PlatformUtils
import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.calls.Call
import io.rebble.libpebblecommon.connection.bt.BluetoothState
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdater
import io.rebble.libpebblecommon.connection.endpointmanager.musiccontrol.MusicTrack
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.CustomTimelineActionHandler
import io.rebble.libpebblecommon.database.asMillisecond
import io.rebble.libpebblecommon.database.dao.AppWithCount
import io.rebble.libpebblecommon.database.dao.ChannelAndCount
import io.rebble.libpebblecommon.database.dao.ContactWithCount
import io.rebble.libpebblecommon.database.entity.CalendarEntity
import io.rebble.libpebblecommon.database.entity.ChannelGroup
import io.rebble.libpebblecommon.database.entity.ChannelItem
import io.rebble.libpebblecommon.database.entity.MuteState
import io.rebble.libpebblecommon.database.entity.NotificationAppItem
import io.rebble.libpebblecommon.database.entity.NotificationEntity
import io.rebble.libpebblecommon.database.entity.TimelineNotification
import io.rebble.libpebblecommon.js.PKJSApp
import io.rebble.libpebblecommon.locker.AppBasicProperties
import io.rebble.libpebblecommon.locker.AppPlatform
import io.rebble.libpebblecommon.locker.AppProperties
import io.rebble.libpebblecommon.locker.AppType
import io.rebble.libpebblecommon.locker.LockerWrapper
import io.rebble.libpebblecommon.metadata.WatchColor
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import io.rebble.libpebblecommon.metadata.WatchType
import io.rebble.libpebblecommon.music.MusicAction
import io.rebble.libpebblecommon.music.PlaybackState
import io.rebble.libpebblecommon.music.RepeatType
import io.rebble.libpebblecommon.notification.NotificationDecision
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
import kotlinx.io.files.Path
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Instant
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

    override suspend fun stopApp(uuid: Uuid) {
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

    override fun getAllLockerBasicInfo(): Flow<List<AppBasicProperties>> {
        return flow { emptyList<AppBasicProperties>() }
    }

    val locker = MutableStateFlow(fakeLockerEntries)

    override fun getLocker(
        type: AppType,
        searchQuery: String?,
        limit: Int
    ): Flow<List<LockerWrapper>> {
        return locker
    }

    override fun getLockerApp(id: Uuid): Flow<LockerWrapper?> {
        return flow { fakeLockerEntries.first() }
    }

    override suspend fun setAppOrder(id: Uuid, order: Int) {

    }

    override suspend fun waitUntilAppSyncedToWatch(
        id: Uuid,
        identifier: PebbleIdentifier,
        timeout: Duration,
    ): Boolean = true

    override suspend fun removeApp(id: Uuid): Boolean = true

    private val _notificationApps = MutableStateFlow(fakeNotificationApps)

    override fun notificationApps(): Flow<List<AppWithCount>> =
        _notificationApps.map { it.map { AppWithCount(it, 0) } }

    override fun notificationAppChannelCounts(packageName: String): Flow<List<ChannelAndCount>> =
        MutableStateFlow(emptyList())

    override fun mostRecentNotificationsFor(
        pkg: String?,
        channelId: String?,
        contactId: String?,
        limit: Int
    ): Flow<List<NotificationEntity>> = flow {
        emit(fakeNotifications)
    }

    private val fakeNotifications by lazy { fakeNotifications() }

    private fun fakeNotifications(): List<NotificationEntity> {
        return buildList {
            for (i in 1..25) {
                add(fakeNotification())
            }
        }
    }

    private fun fakeNotification(): NotificationEntity {
        return NotificationEntity(
            pkg = randomName(),
            key = randomName(),
            groupKey = randomName(),
            channelId = randomName(),
            timestamp = Instant.DISTANT_PAST.asMillisecond(),
            title = randomName(),
            body = randomName(),
            decision = NotificationDecision.SendToWatch,
        )
    }

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
    override fun otherPebbleCompanionAppsInstalled(): StateFlow<List<OtherPebbleApp>> =
        MutableStateFlow(emptyList())

    override suspend fun getAccountToken(appUuid: Uuid): String? {
        return ""
    }

    override val userFacingErrors: Flow<UserFacingError>
        get() = flow { }

    override fun getContactsWithCounts(): Flow<List<ContactWithCount>> {
        return flow { emptyList<ContactWithCount>() }
    }

    override fun updateContactMuteState(
        contactId: String,
        muteState: MuteState
    ) {
    }

    override suspend fun getContactImage(lookupKey: String): ImageBitmap? {
        return null
    }

    override val analyticsEvents: Flow<AnalyticsEvent>
        get() = flow { }
}

fun fakeWatches(): List<PebbleDevice> {
    return buildList {
        for (i in 1..8) {
            add(fakeWatch())
        }
    }
}

fun fakeWatch(connected: Boolean = Random.nextBoolean()): PebbleDevice {
    val num = Random.nextInt(1111, 9999)
    val name = "Core $num"
    val fakeIdentifier = if (PlatformUtils.IS_JVM) {
        randomMacAddress().asPebbleBleIdentifier()
    } else {
        Uuid.random().toString().asPebbleBleIdentifier()
    }
    return if (connected) {
        val updating = Random.nextBoolean()
        val fwupState = if (updating) {
            val fakeUpdate = FirmwareUpdateCheckResult.FoundUpdate(
                version = FirmwareVersion.from(
                    "v4.9.9-core1",
                    isRecovery = false,
                    gitHash = "",
                    timestamp = Instant.DISTANT_PAST,
                    isDualSlot = false,
                    isSlot0 = false,
                )!!,
                url = "",
                notes = "v4.9.9-core1 is great",
            )
            FirmwareUpdater.FirmwareUpdateStatus.InProgress(fakeUpdate, MutableStateFlow(0.47f))
        } else {
            FirmwareUpdater.FirmwareUpdateStatus.NotInProgress.Idle
        }
        val fwupAvailable = if (!updating && Random.nextBoolean()) {
            FirmwareUpdateCheckResult.FoundUpdate(
                version = FirmwareVersion.from(
                    "v4.9.9-core2",
                    isRecovery = false,
                    gitHash = "",
                    timestamp = kotlin.time.Instant.DISTANT_PAST,
                    isDualSlot = false,
                    isSlot0 = false,
                )!!,
                url = "http://something",
                notes = "update!!",
            )
        } else {
            null
        }
        FakeConnectedDevice(
            identifier = fakeIdentifier,
            firmwareUpdateAvailable = fwupAvailable,
            firmwareUpdateState = fwupState,
            name = name,
            nickname = null,
            connectionFailureInfo = null,
        )
    } else {
        object : DiscoveredPebbleDevice {
            override val identifier = fakeIdentifier
            override val name: String = "Fake 1234"
            override val nickname: String? = "Faker 1234"
            override val connectionFailureInfo: ConnectionFailureInfo? = null

            override fun connect() {
            }
        }
    }
}

class FakeConnectedDevice(
    override val identifier: PebbleIdentifier,
    override val firmwareUpdateAvailable: FirmwareUpdateCheckResult?,
    override val firmwareUpdateState: FirmwareUpdater.FirmwareUpdateStatus,
    override val name: String,
    override val nickname: String?,
    override val color: WatchColor = run {
        val white = Random.nextBoolean()
        if (white) {
            WatchColor.Pebble2DuoWhite
        } else {
            WatchColor.Pebble2DuoBlack
        }
    },
    override val watchType: WatchHardwarePlatform = WatchHardwarePlatform.CORE_ASTERIX,
    override val lastConnected: Instant = Instant.DISTANT_PAST,
    override val serial: String = "XXXXXXXXXXXX",
    override val runningFwVersion: String = "v1.2.3-core",
    override val connectionFailureInfo: ConnectionFailureInfo?,
    override val usingBtClassic: Boolean = false,
) : ConnectedPebbleDevice {

    override fun forget() {}
    override fun setNickname(nickname: String?) {
    }

    override fun connect() {}

    override fun disconnect() {}

    override suspend fun sendPing(cookie: UInt): UInt = cookie

    override fun resetIntoPrf() {}

    override fun createCoreDump() {}

    override suspend fun sendPPMessage(bytes: ByteArray) {}

    override suspend fun sendPPMessage(ppMessage: PebblePacket) {}

    override val inboundMessages: Flow<PebblePacket> = MutableSharedFlow()
    override val rawInboundMessages: Flow<ByteArray> = MutableSharedFlow()

    override fun sideloadFirmware(path: Path) {}

    override fun updateFirmware(update: FirmwareUpdateCheckResult.FoundUpdate) {}

    override fun checkforFirmwareUpdate() {}

    override suspend fun launchApp(uuid: Uuid) {}

    override suspend fun stopApp(uuid: Uuid) {}

    override val runningApp: StateFlow<Uuid?> = MutableStateFlow(null)
    override val watchInfo: WatchInfo = WatchInfo(
        runningFwVersion = FirmwareVersion.from(
            runningFwVersion,
            isRecovery = false,
            gitHash = "",
            timestamp = kotlin.time.Instant.DISTANT_PAST,
            isDualSlot = false,
            isSlot0 = false,
        )!!,
        recoveryFwVersion = FirmwareVersion.from(
            runningFwVersion,
            isRecovery = true,
            gitHash = "",
            timestamp = kotlin.time.Instant.DISTANT_PAST,
            isDualSlot = false,
            isSlot0 = false,
        )!!,
        platform = watchType,
        bootloaderTimestamp = kotlin.time.Instant.DISTANT_PAST,
        board = "board",
        serial = serial,
        btAddress = "11:22:33:44:55:66",
        resourceCrc = -9999999,
        resourceTimestamp = kotlin.time.Instant.DISTANT_PAST,
        language = "en-GB",
        languageVersion = 1,
        capabilities = emptySet(),
        isUnfaithful = false,
        healthInsightsVersion = null,
        javascriptVersion = null,
        color = color,
    )

    override suspend fun updateTime() {}

    override fun inboundAppMessages(appUuid: Uuid): Flow<AppMessageData> {
        return MutableSharedFlow()
    }

    override val transactionSequence: Iterator<UByte> = iterator { }

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
    ) {
    }

    override suspend fun updatePlayerInfo(packageId: String, name: String) {}

    override suspend fun updateVolumeInfo(volumePercent: UByte) {}

    override val musicActions: Flow<MusicAction> = MutableSharedFlow()
    override val updateRequestTrigger: Flow<Unit> = MutableSharedFlow()
    @Deprecated("Use more generic currentCompanionAppSession instead and cast if necessary")
    override val currentPKJSSession: StateFlow<PKJSApp?> = MutableStateFlow(null)
    override val currentCompanionAppSession: StateFlow<CompanionApp?> = MutableStateFlow(null)

    override suspend fun startDevConnection() {}
    override suspend fun stopDevConnection() {}
    override val devConnectionActive: StateFlow<Boolean> = MutableStateFlow(false)
    override val batteryLevel: Int? = 50
    override suspend fun takeScreenshot(): ImageBitmap {
        // Return an orange square as a placeholder
        val width = 144
        val height = 168
        val buffer = IntArray(width * height) { Color(0xFFFA4A36).toArgb() }
        return ImageBitmap(width, height).apply { readPixels(buffer) }
    }

    override suspend fun installLanguagePack(path: Path): Boolean {
        return true
    }
}

private val fakeNotificationApps by lazy { fakeNotificationApps() }

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
        for (i in 1..Random.nextInt(2, 5)) {
            add(
                ChannelGroup(
                    id = randomName(),
                    name = randomName(),
                    channels = fakeChannels(),
                )
            )
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

val fakeLockerEntries by lazy { fakeLockerEntries() }

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
            version = "1.0",
            hearts = 50,
            category = "fun stuff",
            iosCompanion = null,
            androidCompanion = null,
            order = 0,
        ),
        sideloaded = false,
        configurable = Random.nextBoolean(),
        sync = true,
    )
}
