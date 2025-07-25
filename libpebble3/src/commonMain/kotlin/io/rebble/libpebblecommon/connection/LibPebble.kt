package io.rebble.libpebblecommon.connection

import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.ImageBitmap
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.Housekeeping
import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.LibPebbleConfigHolder
import io.rebble.libpebblecommon.calendar.PhoneCalendarSyncer
import io.rebble.libpebblecommon.calls.Call
import io.rebble.libpebblecommon.calls.MissedCallSyncer
import io.rebble.libpebblecommon.connection.bt.BluetoothState
import io.rebble.libpebblecommon.connection.bt.BluetoothStateProvider
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattServerManager
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.ActionOverrides
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.CustomTimelineActionHandler
import io.rebble.libpebblecommon.database.dao.AppWithCount
import io.rebble.libpebblecommon.database.dao.ChannelAndCount
import io.rebble.libpebblecommon.database.dao.TimelineNotificationRealDao
import io.rebble.libpebblecommon.database.entity.CalendarEntity
import io.rebble.libpebblecommon.database.entity.MuteState
import io.rebble.libpebblecommon.database.entity.NotificationEntity
import io.rebble.libpebblecommon.database.entity.TimelineNotification
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.di.initKoin
import io.rebble.libpebblecommon.health.Health
import io.rebble.libpebblecommon.js.JsTokenUtil
import io.rebble.libpebblecommon.locker.Locker
import io.rebble.libpebblecommon.locker.LockerWrapper
import io.rebble.libpebblecommon.notification.NotificationApi
import io.rebble.libpebblecommon.notification.NotificationListenerConnection
import io.rebble.libpebblecommon.packets.ProtocolCapsFlag
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.services.WatchInfo
import io.rebble.libpebblecommon.time.TimeChanged
import io.rebble.libpebblecommon.web.LockerModel
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.io.files.Path
import org.koin.core.Koin
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.uuid.Uuid

data class PhoneCapabilities(val capabilities: Set<ProtocolCapsFlag>)
data class PlatformFlags(val flags: UInt)

typealias PebbleDevices = StateFlow<List<PebbleDevice>>

sealed class PebbleConnectionEvent {
    data class PebbleConnectedEvent(val device: CommonConnectedDevice) : PebbleConnectionEvent()
    data class PebbleDisconnectedEvent(val device: Transport) : PebbleConnectionEvent()
}

@Stable
interface LibPebble : Scanning, RequestSync, LockerApi, NotificationApps, CallManagement, Calendar, OtherPebbleApps, PKJSToken, Watches {
    fun init()

    val config: StateFlow<LibPebbleConfig>
    fun updateConfig(config: LibPebbleConfig)

    // Generally, use these. They will act on all watches (or all connected watches, if that makes
    // sense)
    suspend fun sendNotification(notification: TimelineNotification, actionHandlers: Map<UByte, CustomTimelineActionHandler>? = null)
    suspend fun markNotificationRead(itemId: Uuid)
    suspend fun sendPing(cookie: UInt)
    suspend fun launchApp(uuid: Uuid)
    // ....

    fun doStuffAfterPermissionsGranted()
    fun checkForFirmwareUpdates()
}

data class OtherPebbleApp(
    val pkg: String,
    val name: String,
)

interface Watches {
    val watches: PebbleDevices
    val connectionEvents: Flow<PebbleConnectionEvent>
    fun watchesDebugState(): String
}

interface WebServices {
    suspend fun fetchLocker(): LockerModel?
    suspend fun checkForFirmwareUpdate(watch: WatchInfo): FirmwareUpdateCheckResult?
    suspend fun uploadMemfaultChunk(chunk: ByteArray, watchInfo: WatchInfo)
}

interface TokenProvider {
    suspend fun getDevToken(): String?
}

data class FirmwareUpdateCheckResult(
    val version: FirmwareVersion,
    val url: String,
    val notes: String,
)

interface Calendar {
    fun calendars(): Flow<List<CalendarEntity>>
    fun updateCalendarEnabled(calendarId: Int, enabled: Boolean)
}

fun PebbleDevices.forDevice(transport: Transport): Flow<PebbleDevice> {
    return mapNotNull { it.firstOrNull { it.transport == transport } }
}

interface Scanning {
    val bluetoothEnabled: StateFlow<BluetoothState>
    val isScanningBle: StateFlow<Boolean>
    fun startBleScan()
    fun stopBleScan()
    fun startClassicScan()
    fun stopClassicScan()
}

interface RequestSync {
    fun requestLockerSync(): Deferred<Unit>
}

interface LockerApi {
    /**
     * @return true if the app was successfully synced and launched on all connected watches.
     */
    suspend fun sideloadApp(pbwPath: Path): Boolean
    fun getLocker(): Flow<List<LockerWrapper>>
    suspend fun setAppOrder(id: Uuid, order: Int)
}

@Stable
interface NotificationApps {
    val notificationApps: Flow<List<AppWithCount>>
    fun notificationAppChannelCounts(packageName: String): Flow<List<ChannelAndCount>>
    fun mostRecentNotificationsFor(pkg: String, channelId: String?, limit: Int): Flow<List<NotificationEntity>>
    fun updateNotificationAppMuteState(packageName: String, muteState: MuteState)
    fun updateNotificationChannelMuteState(
        packageName: String,
        channelId: String,
        muteState: MuteState,
    )

    /** Will only return a value on Android */
    suspend fun getAppIcon(packageName: String): ImageBitmap?
}

interface OtherPebbleApps {
    /** Any other companion apps installed will likely break connecitivity (multiple PPoG services) */
    val otherPebbleCompanionAppsInstalled: StateFlow<List<OtherPebbleApp>>
}

interface CallManagement {
    val currentCall: MutableStateFlow<Call?>
}

interface PKJSToken {
    suspend fun getAccountToken(appUuid: Uuid): String?
}

// Impl

class LibPebble3(
    private val watchManager: WatchManager,
    private val scanning: Scanning,
    private val locker: Locker,
    private val timeChanged: TimeChanged,
    private val webSyncManager: RequestSync,
    private val libPebbleCoroutineScope: LibPebbleCoroutineScope,
    private val gattServerManager: GattServerManager,
    private val bluetoothStateProvider: BluetoothStateProvider,
    private val notificationListenerConnection: NotificationListenerConnection,
    private val notificationApi: NotificationApi,
    private val timelineNotificationsDao: TimelineNotificationRealDao,
    private val actionOverrides: ActionOverrides,
    private val phoneCalendarSyncer: PhoneCalendarSyncer,
    private val missedCallSyncer: MissedCallSyncer,
    private val libPebbleConfigFlow: LibPebbleConfigHolder,
    private val health: Health,
    private val otherPebbleApps: OtherPebbleApps,
    private val jsTokenUtil: JsTokenUtil,
    private val housekeeping: Housekeeping,
) : LibPebble, Scanning by scanning, RequestSync by webSyncManager, LockerApi by locker,
    NotificationApps by notificationApi, Calendar by phoneCalendarSyncer,
    OtherPebbleApps by otherPebbleApps, PKJSToken by jsTokenUtil, Watches by watchManager {
    private val logger = Logger.withTag("LibPebble3")
    private val initialized = AtomicBoolean(false)

    override fun init() {
        if (!initialized.compareAndSet(expectedValue = false, newValue = true)) {
            logger.w { "Already initialized!!!" }
            return
        }
        bluetoothStateProvider.init()
        gattServerManager.init()
        watchManager.init()
        phoneCalendarSyncer.init()
        missedCallSyncer.init()
        notificationListenerConnection.init(this)
        notificationApi.init()
        health.init()
        timeChanged.registerForTimeChanges {
            logger.d("Time changed")
            libPebbleCoroutineScope.launch { forEachConnectedWatch { updateTime() } }
        }
        housekeeping.init()
    }

    override val config: StateFlow<LibPebbleConfig> = libPebbleConfigFlow.config

    override fun updateConfig(config: LibPebbleConfig) {
        logger.d("Updated config: $config")
        libPebbleConfigFlow.update(config)
    }

    override val currentCall: MutableStateFlow<Call?> = MutableStateFlow(null)

    override suspend fun sendNotification(notification: TimelineNotification, actionHandlers: Map<UByte, CustomTimelineActionHandler>?) {
        timelineNotificationsDao.insertOrReplace(notification)
        actionHandlers?.let { actionOverrides.setActionHandlers(notification.itemId, actionHandlers) }
    }

    override suspend fun markNotificationRead(itemId: Uuid) {
        timelineNotificationsDao.markNotificationRead(itemId)
        actionOverrides.setActionHandlers(itemId, emptyMap())
    }

    override suspend fun sendPing(cookie: UInt) {
        forEachConnectedWatch { sendPing(cookie) }
    }

    override suspend fun launchApp(uuid: Uuid) {
        forEachConnectedWatch { launchApp(uuid) }
    }

    override fun doStuffAfterPermissionsGranted() {
        phoneCalendarSyncer.init()
        missedCallSyncer.init()
    }

    override fun checkForFirmwareUpdates() {
        forEachConnectedWatchNonSuspend { checkforFirmwareUpdate() }
    }

    private suspend fun forEachConnectedWatch(block: suspend ConnectedPebbleDevice.() -> Unit) {
        watches.value.filterIsInstance<ConnectedPebbleDevice>().forEach {
            it.block()
        }
    }

    private fun forEachConnectedWatchNonSuspend(block: ConnectedPebbleDevice.() -> Unit) {
        watches.value.filterIsInstance<ConnectedPebbleDevice>().forEach {
            it.block()
        }
    }

    companion object {
        private lateinit var koin: Koin

        fun create(
            /**
             * Default config, before any changes are made.
             *
             * Config will be persisted - this parameter will only be used on the first init.
             */
            defaultConfig: LibPebbleConfig,
            webServices: WebServices,
            appContext: AppContext,
            tokenProvider: TokenProvider,
        ): LibPebble {
            koin = initKoin(defaultConfig, webServices, appContext, tokenProvider)
            val libPebble = koin.get<LibPebble>()
            return libPebble
        }
    }
}