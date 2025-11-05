package io.rebble.libpebblecommon.connection

import androidx.compose.runtime.Stable
import androidx.compose.ui.graphics.ImageBitmap
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.ErrorTracker
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
import io.rebble.libpebblecommon.contacts.PhoneContactsSyncer
import io.rebble.libpebblecommon.database.dao.AppWithCount
import io.rebble.libpebblecommon.database.dao.ChannelAndCount
import io.rebble.libpebblecommon.database.dao.ContactWithCount
import io.rebble.libpebblecommon.database.dao.TimelineNotificationRealDao
import io.rebble.libpebblecommon.database.entity.CalendarEntity
import io.rebble.libpebblecommon.database.entity.MuteState
import io.rebble.libpebblecommon.database.entity.NotificationEntity
import io.rebble.libpebblecommon.database.entity.TimelineNotification
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.di.initKoin
import io.rebble.libpebblecommon.health.Health
import io.rebble.libpebblecommon.js.JsTokenUtil
import io.rebble.libpebblecommon.locker.AppBasicProperties
import io.rebble.libpebblecommon.locker.AppType
import io.rebble.libpebblecommon.locker.Locker
import io.rebble.libpebblecommon.locker.LockerWrapper
import io.rebble.libpebblecommon.notification.NotificationApi
import io.rebble.libpebblecommon.notification.NotificationListenerConnection
import io.rebble.libpebblecommon.packets.ProtocolCapsFlag
import io.rebble.libpebblecommon.performPlatformSpecificInit
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.services.WatchInfo
import io.rebble.libpebblecommon.time.TimeChanged
import io.rebble.libpebblecommon.voice.TranscriptionProvider
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
import kotlin.time.Duration
import kotlin.uuid.Uuid

data class PhoneCapabilities(val capabilities: Set<ProtocolCapsFlag>)
data class PlatformFlags(val flags: UInt)

typealias PebbleDevices = StateFlow<List<PebbleDevice>>

sealed class PebbleConnectionEvent {
    data class PebbleConnectedEvent(val device: CommonConnectedDevice) : PebbleConnectionEvent()
    data class PebbleDisconnectedEvent(val identifier: PebbleIdentifier) : PebbleConnectionEvent()
}

@Stable
interface LibPebble : Scanning, RequestSync, LockerApi, NotificationApps, CallManagement, Calendar,
    OtherPebbleApps, PKJSToken, Watches, Errors, Contacts, AnalyticsEvents {
    fun init()

    val config: StateFlow<LibPebbleConfig>
    fun updateConfig(config: LibPebbleConfig)

    // Generally, use these. They will act on all watches (or all connected watches, if that makes
    // sense)
    suspend fun sendNotification(notification: TimelineNotification, actionHandlers: Map<UByte, CustomTimelineActionHandler>? = null)
    suspend fun markNotificationRead(itemId: Uuid)
    suspend fun sendPing(cookie: UInt)
    suspend fun launchApp(uuid: Uuid)
    suspend fun stopApp(uuid: Uuid)
    // ....

    fun doStuffAfterPermissionsGranted()
    fun checkForFirmwareUpdates()
}

sealed class UserFacingError {
    abstract val message: String

    data class FailedToDownloadPbw(override val message: String) : UserFacingError()
    data class FailedToRemovePbwFromLocker(override val message: String) : UserFacingError()
    data class FailedToSideloadApp(override val message: String) : UserFacingError()
    data class FailedToScan(override val message: String) : UserFacingError()
}

data class OtherPebbleApp(
    val pkg: String,
    val name: String,
)

interface Errors {
    /**
     * Errors which should be displayed to the user (e.g. using a snackbar).
     */
    val userFacingErrors: Flow<UserFacingError>
}

data class AnalyticsEvent(
    val name: String,
    val parameters: Map<String, String>,
)

interface AnalyticsEvents {
    val analyticsEvents: Flow<AnalyticsEvent>
}

interface Watches {
    val watches: PebbleDevices
    val connectionEvents: Flow<PebbleConnectionEvent>
    fun watchesDebugState(): String
}

interface WebServices {
    suspend fun fetchLocker(): LockerModel?
    suspend fun removeFromLocker(id: Uuid): Boolean
    suspend fun checkForFirmwareUpdate(watch: WatchInfo): FirmwareUpdateCheckResult
    suspend fun uploadMemfaultChunk(chunk: ByteArray, watchInfo: WatchInfo)
}

interface TokenProvider {
    suspend fun getDevToken(): String?
}

sealed class FirmwareUpdateCheckResult {
    data class FoundUpdate(
        val version: FirmwareVersion,
        val url: String,
        val notes: String,
    ) : FirmwareUpdateCheckResult()

    data object FoundNoUpdate : FirmwareUpdateCheckResult()

    data class UpdateCheckFailed(
        val error: String,
    ) : FirmwareUpdateCheckResult()
}

interface Calendar {
    fun calendars(): Flow<List<CalendarEntity>>
    fun updateCalendarEnabled(calendarId: Int, enabled: Boolean)
}

fun PebbleDevices.forDevice(identifier: String): Flow<PebbleDevice> {
    return mapNotNull { it.firstOrNull { it.identifier.asString == identifier } }
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
    fun getAllLockerBasicInfo(): Flow<List<AppBasicProperties>>
    fun getLocker(type: AppType, searchQuery: String?, limit: Int): Flow<List<LockerWrapper>>
    fun getLockerApp(id: Uuid): Flow<LockerWrapper?>
    suspend fun setAppOrder(id: Uuid, order: Int)
    suspend fun waitUntilAppSyncedToWatch(id: Uuid, identifier: PebbleIdentifier, timeout: Duration): Boolean
    suspend fun removeApp(id: Uuid): Boolean
}

interface Contacts {
    fun getContactsWithCounts(): Flow<List<ContactWithCount>>
    fun updateContactMuteState(contactId: String, muteState: MuteState)
    suspend fun getContactImage(lookupKey: String): ImageBitmap?
}

@Stable
interface NotificationApps {
    fun notificationApps(): Flow<List<AppWithCount>>
    fun notificationAppChannelCounts(packageName: String): Flow<List<ChannelAndCount>>
    fun mostRecentNotificationsFor(pkg: String?, channelId: String?, contactId: String?, limit: Int): Flow<List<NotificationEntity>>
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
    fun otherPebbleCompanionAppsInstalled(): StateFlow<List<OtherPebbleApp>>
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
    private val errorTracker: ErrorTracker,
    private val phoneContactsSyncer: PhoneContactsSyncer,
    private val contacts: Contacts,
    private val analytics: AnalyticsEvents,
) : LibPebble, Scanning by scanning, RequestSync by webSyncManager, LockerApi by locker,
    NotificationApps by notificationApi, Calendar by phoneCalendarSyncer,
    OtherPebbleApps by otherPebbleApps, PKJSToken by jsTokenUtil, Watches by watchManager,
    Errors by errorTracker, Contacts by contacts, AnalyticsEvents by analytics {
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
        phoneContactsSyncer.init()
        missedCallSyncer.init()
        notificationListenerConnection.init(this)
        notificationApi.init()
        health.init()
        timeChanged.registerForTimeChanges {
            logger.d("Time changed")
            libPebbleCoroutineScope.launch { forEachConnectedWatch { updateTime() } }
        }
        housekeeping.init()

        performPlatformSpecificInit()
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

    override suspend fun stopApp(uuid: Uuid) {
        forEachConnectedWatch { stopApp(uuid) }
    }

    override fun doStuffAfterPermissionsGranted() {
        phoneCalendarSyncer.init()
        missedCallSyncer.init()
        phoneContactsSyncer.init()
    }

    override fun checkForFirmwareUpdates() {
        forEachConnectedWatchInAnyState { checkforFirmwareUpdate() }
    }

    private suspend fun forEachConnectedWatch(block: suspend ConnectedPebbleDevice.() -> Unit) {
        watches.value.filterIsInstance<ConnectedPebbleDevice>().forEach {
            it.block()
        }
    }

    private fun forEachConnectedWatchInAnyState(block: CommonConnectedDevice.() -> Unit) {
        watches.value.filterIsInstance<CommonConnectedDevice>().forEach {
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
            proxyTokenProvider: StateFlow<String?>,
            transcriptionProvider: TranscriptionProvider
        ): LibPebble {
            koin = initKoin(defaultConfig, webServices, appContext, tokenProvider, proxyTokenProvider, transcriptionProvider)
            val libPebble = koin.get<LibPebble>()
            return libPebble
        }
    }
}
