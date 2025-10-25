package io.rebble.libpebblecommon.di

import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import io.ktor.client.HttpClient
import io.rebble.libpebblecommon.BleConfigFlow
import io.rebble.libpebblecommon.ErrorTracker
import io.rebble.libpebblecommon.Housekeeping
import io.rebble.libpebblecommon.LibPebbleAnalytics
import io.rebble.libpebblecommon.LibPebbleConfig
import io.rebble.libpebblecommon.LibPebbleConfigFlow
import io.rebble.libpebblecommon.LibPebbleConfigHolder
import io.rebble.libpebblecommon.NotificationConfigFlow
import io.rebble.libpebblecommon.RealLibPebbleAnalytics
import io.rebble.libpebblecommon.WatchConfigFlow
import io.rebble.libpebblecommon.calendar.PhoneCalendarSyncer
import io.rebble.libpebblecommon.calls.MissedCallSyncer
import io.rebble.libpebblecommon.connection.AnalyticsEvents
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.ConnectionFailureHandler
import io.rebble.libpebblecommon.connection.Contacts
import io.rebble.libpebblecommon.connection.CreatePlatformIdentifier
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.LibPebble3
import io.rebble.libpebblecommon.connection.Negotiator
import io.rebble.libpebblecommon.connection.NotificationApps
import io.rebble.libpebblecommon.connection.PebbleBleIdentifier
import io.rebble.libpebblecommon.connection.PebbleConnector
import io.rebble.libpebblecommon.connection.PebbleDeviceFactory
import io.rebble.libpebblecommon.connection.PebbleIdentifier
import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.connection.PebbleProtocolRunner
import io.rebble.libpebblecommon.connection.PebbleProtocolStreams
import io.rebble.libpebblecommon.connection.PebbleSocketIdentifier
import io.rebble.libpebblecommon.connection.PlatformIdentifier
import io.rebble.libpebblecommon.connection.RealConnectionFailureHandler
import io.rebble.libpebblecommon.connection.RealCreatePlatformIdentifier
import io.rebble.libpebblecommon.connection.RealPebbleConnector
import io.rebble.libpebblecommon.connection.RealPebbleProtocolHandler
import io.rebble.libpebblecommon.connection.RealScanning
import io.rebble.libpebblecommon.connection.RequestSync
import io.rebble.libpebblecommon.connection.Scanning
import io.rebble.libpebblecommon.connection.TokenProvider
import io.rebble.libpebblecommon.connection.TransportConnector
import io.rebble.libpebblecommon.connection.WatchConnector
import io.rebble.libpebblecommon.connection.WatchManager
import io.rebble.libpebblecommon.connection.WebServices
import io.rebble.libpebblecommon.connection.bt.BluetoothStateProvider
import io.rebble.libpebblecommon.connection.bt.RealBluetoothStateProvider
import io.rebble.libpebblecommon.connection.bt.ble.pebble.BatteryWatcher
import io.rebble.libpebblecommon.connection.bt.ble.pebble.ConnectionParams
import io.rebble.libpebblecommon.connection.bt.ble.pebble.ConnectivityWatcher
import io.rebble.libpebblecommon.connection.bt.ble.pebble.Mtu
import io.rebble.libpebblecommon.connection.bt.ble.pebble.PebbleBle
import io.rebble.libpebblecommon.connection.bt.ble.pebble.PebblePairing
import io.rebble.libpebblecommon.connection.bt.ble.pebble.PpogClient
import io.rebble.libpebblecommon.connection.bt.ble.pebble.PpogServer
import io.rebble.libpebblecommon.connection.bt.ble.pebble.PreConnectScanner
import io.rebble.libpebblecommon.connection.bt.ble.ppog.PPoG
import io.rebble.libpebblecommon.connection.bt.ble.ppog.PPoGPacketSender
import io.rebble.libpebblecommon.connection.bt.ble.ppog.PPoGStream
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattConnector
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattServerManager
import io.rebble.libpebblecommon.connection.bt.ble.transport.bleScanner
import io.rebble.libpebblecommon.connection.bt.ble.transport.impl.KableGattConnector
import io.rebble.libpebblecommon.connection.bt.classic.pebble.PebbleBtClassic
import io.rebble.libpebblecommon.connection.devconnection.DevConnectionCloudpebbleProxy
import io.rebble.libpebblecommon.connection.devconnection.DevConnectionManager
import io.rebble.libpebblecommon.connection.devconnection.DevConnectionServer
import io.rebble.libpebblecommon.connection.endpointmanager.AppFetchProvider
import io.rebble.libpebblecommon.connection.endpointmanager.AppOrderManager
import io.rebble.libpebblecommon.connection.endpointmanager.DebugPebbleProtocolSender
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdater
import io.rebble.libpebblecommon.connection.endpointmanager.LanguagePackInstaller
import io.rebble.libpebblecommon.connection.endpointmanager.CompanionAppLifecycleManager
import io.rebble.libpebblecommon.connection.endpointmanager.RealFirmwareUpdater
import io.rebble.libpebblecommon.connection.endpointmanager.audio.VoiceSessionManager
import io.rebble.libpebblecommon.connection.endpointmanager.blobdb.BlobDB
import io.rebble.libpebblecommon.connection.endpointmanager.blobdb.BlobDbDaos
import io.rebble.libpebblecommon.connection.endpointmanager.blobdb.RealTimeProvider
import io.rebble.libpebblecommon.connection.endpointmanager.blobdb.TimeProvider
import io.rebble.libpebblecommon.connection.endpointmanager.musiccontrol.MusicControlManager
import io.rebble.libpebblecommon.connection.endpointmanager.phonecontrol.PhoneControlManager
import io.rebble.libpebblecommon.connection.endpointmanager.putbytes.PutBytesSession
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.ActionOverrides
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.TimelineActionManager
import io.rebble.libpebblecommon.contacts.PhoneContactsSyncer
import io.rebble.libpebblecommon.database.BlobDbDatabaseManager
import io.rebble.libpebblecommon.database.Database
import io.rebble.libpebblecommon.database.RealBlobDbDatabaseManager
import io.rebble.libpebblecommon.database.dao.LockerEntryRealDao
import io.rebble.libpebblecommon.database.dao.NotificationAppRealDao
import io.rebble.libpebblecommon.database.dao.TimelineNotificationRealDao
import io.rebble.libpebblecommon.database.entity.LockerEntryDao
import io.rebble.libpebblecommon.database.entity.NotificationAppItemDao
import io.rebble.libpebblecommon.database.entity.TimelineNotificationDao
import io.rebble.libpebblecommon.database.getRoomDatabase
import io.rebble.libpebblecommon.datalogging.Datalogging
import io.rebble.libpebblecommon.health.Health
import io.rebble.libpebblecommon.js.JsTokenUtil
import io.rebble.libpebblecommon.locker.Locker
import io.rebble.libpebblecommon.locker.LockerPBWCache
import io.rebble.libpebblecommon.locker.StaticLockerPBWCache
import io.rebble.libpebblecommon.locker.WebSyncManagerProvider
import io.rebble.libpebblecommon.metadata.WatchColor
import io.rebble.libpebblecommon.notification.ContactsApi
import io.rebble.libpebblecommon.notification.NotificationApi
import io.rebble.libpebblecommon.services.AppFetchService
import io.rebble.libpebblecommon.services.AppReorderService
import io.rebble.libpebblecommon.services.AudioStreamService
import io.rebble.libpebblecommon.services.DataLoggingService
import io.rebble.libpebblecommon.services.GetBytesService
import io.rebble.libpebblecommon.services.LogDumpService
import io.rebble.libpebblecommon.services.MusicService
import io.rebble.libpebblecommon.services.PhoneControlService
import io.rebble.libpebblecommon.services.PutBytesService
import io.rebble.libpebblecommon.services.ScreenshotService
import io.rebble.libpebblecommon.services.SystemService
import io.rebble.libpebblecommon.services.VoiceService
import io.rebble.libpebblecommon.services.app.AppRunStateService
import io.rebble.libpebblecommon.services.appmessage.AppMessageService
import io.rebble.libpebblecommon.services.blobdb.BlobDBService
import io.rebble.libpebblecommon.services.blobdb.TimelineService
import io.rebble.libpebblecommon.time.createTimeChanged
import io.rebble.libpebblecommon.util.PrivateLogger
import io.rebble.libpebblecommon.voice.TranscriptionProvider
import io.rebble.libpebblecommon.web.FirmwareDownloader
import io.rebble.libpebblecommon.web.FirmwareUpdateManager
import io.rebble.libpebblecommon.web.RealFirmwareUpdateManager
import io.rebble.libpebblecommon.web.WebSyncManager
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import org.koin.core.Koin
import org.koin.core.component.KoinComponent
import org.koin.core.module.Module
import org.koin.core.module.dsl.scopedOf
import org.koin.core.module.dsl.singleOf
import org.koin.core.scope.Scope
import org.koin.dsl.bind
import org.koin.dsl.binds
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock
import kotlin.uuid.Uuid

data class ConnectionScopeProperties(
    val identifier: PebbleIdentifier,
    val scope: ConnectionCoroutineScope,
    val platformIdentifier: PlatformIdentifier,
    val color: WatchColor,
    val useBtClassic: UseBtClassicAddress,
)

// TODO Address won't work when/if we add iOS classic support
data class UseBtClassicAddress(val address: String?)

interface ConnectionAnalyticsLogger {
    fun logEvent(name: String, props: Map<String, String>? = null)
}

class RealConnectionAnalyticsLogger(
    private val connectionProps: ConnectionScopeProperties,
    private val analytics: LibPebbleAnalytics,
) : ConnectionAnalyticsLogger {
    override fun logEvent(
        name: String,
        props: Map<String, String>?,
    ) {
        analytics.logWatchEvent(connectionProps.color, "watch.$name", props)
    }
}

fun LibPebbleAnalytics.logWatchEvent(
    color: WatchColor,
    name: String,
    props: Map<String, String>? = null,
) {
    logEvent(
        "watch.$name", (props ?: emptyMap()) +
                mapOf(
                    "color" to color.jsName,
                    "platform" to color.platform.name,
                )
    )
}

interface ConnectionScope {
    val identifier: PebbleIdentifier
    val pebbleConnector: PebbleConnector
    fun close()
    val closed: AtomicBoolean
    val firmwareUpdateManager: FirmwareUpdateManager
    val firmwareUpdater: FirmwareUpdater
    val batteryWatcher: BatteryWatcher
    val analyticsLogger: ConnectionAnalyticsLogger
    val usingBtClassic: Boolean
}

class RealConnectionScope(
    private val koinScope: Scope,
    override val identifier: PebbleIdentifier,
    private val coroutineScope: ConnectionCoroutineScope,
    private val uuid: Uuid,
    override val closed: AtomicBoolean = AtomicBoolean(false),
    override val firmwareUpdateManager: FirmwareUpdateManager,
) : ConnectionScope {
    override val pebbleConnector: PebbleConnector = koinScope.get()
    override val firmwareUpdater: FirmwareUpdater = koinScope.get()
    override val batteryWatcher: BatteryWatcher = koinScope.get()
    override val analyticsLogger: ConnectionAnalyticsLogger = koinScope.get()
    override val usingBtClassic: Boolean = koinScope.get<UseBtClassicAddress>().address != null

    override fun close() {
        Logger.d("close ConnectionScope: $koinScope / $uuid")
        coroutineScope.cancel()
        koinScope.close()
    }
}

data class PlatformConfig(
    val syncNotificationApps: Boolean,
)

interface ConnectionScopeFactory {
    fun createScope(props: ConnectionScopeProperties): ConnectionScope
}

class RealConnectionScopeFactory(private val koin: Koin) : ConnectionScopeFactory {
    override fun createScope(props: ConnectionScopeProperties): ConnectionScope {
        val uuid = Uuid.random()
        val scope =
            koin.createScope<ConnectionScope>("${props.identifier.asString}-$uuid", props)
        Logger.d("scope: $scope / $uuid")
        return RealConnectionScope(
            koinScope = scope,
            identifier = props.identifier,
            coroutineScope = props.scope,
            uuid = uuid,
            firmwareUpdateManager = scope.get(),
        )
    }
}

/**
 * Essentially, GlobalScope for libpebble. Use this everywhere that would otherwise use GlobalScope.
 */
class LibPebbleCoroutineScope(override val coroutineContext: CoroutineContext) : CoroutineScope

/**
 * Per-connection coroutine scope, torn down when the connection ends.
 */
class ConnectionCoroutineScope(override val coroutineContext: CoroutineContext) : CoroutineScope

/**
 * Lazy/provider for when we need to get out of a circular dependency.
 */
class HackyProvider<T>(val getter: () -> T) {
    fun get(): T = getter()
}

expect val platformModule: Module

// https://insert-koin.io/docs/reference/koin-core/context-isolation/
private object LibPebbleKoinContext {
    private val koinApp = koinApplication()
    val koin = koinApp.koin
}

internal interface LibPebbleKoinComponent : KoinComponent {
    override fun getKoin(): Koin = LibPebbleKoinContext.koin
}

fun initKoin(
    defaultConfig: LibPebbleConfig,
    webServices: WebServices,
    appContext: AppContext,
    tokenProvider: TokenProvider,
    proxyTokenProvider: StateFlow<String?>,
    transcriptionProvider: TranscriptionProvider
): Koin {
    val koin = LibPebbleKoinContext.koin
    val libPebbleScope = LibPebbleCoroutineScope(CoroutineName("libpebble3"))
    koin.loadModules(
        listOf(
            module {
                includes(platformModule, pkjsPlatformModule)

                single { LibPebbleConfigHolder(defaultValue = defaultConfig, get(), get()) }
                single { LibPebbleConfigFlow(get<LibPebbleConfigHolder>().config) }
                single { WatchConfigFlow(get<LibPebbleConfigHolder>().config) }
                single { BleConfigFlow(get<LibPebbleConfigHolder>().config) }
                single { NotificationConfigFlow(get<LibPebbleConfigHolder>().config) }

                single { Settings() }
                single { appContext }
                single { webServices }
                single { tokenProvider }
                single { transcriptionProvider }
                single { getRoomDatabase(get()) }
                singleOf(::StaticLockerPBWCache) bind LockerPBWCache::class
                singleOf(::PebbleDeviceFactory)
                single { get<Database>().knownWatchDao() }
                single { get<Database>().lockerEntryDao() } binds arrayOf(LockerEntryDao::class, LockerEntryRealDao::class)
                single { get<Database>().notificationAppDao() } binds arrayOf(NotificationAppItemDao::class, NotificationAppRealDao::class)
                single { get<Database>().timelineNotificationDao() } binds arrayOf(TimelineNotificationDao::class, TimelineNotificationRealDao::class)
                single { get<Database>().timelinePinDao() }
                single { get<Database>().timelineReminderDao() }
                single { get<Database>().calendarDao() }
                single { get<Database>().watchSettingsDao() }
                single { get<Database>().lockerAppPermissionDao() }
                single { get<Database>().notificationsDao() }
                single { get<Database>().contactDao() }
                singleOf(::WatchManager) bind WatchConnector::class
                single { bleScanner() }
                singleOf(::RealScanning) bind Scanning::class
                single { libPebbleScope }
                singleOf(::Locker)
                singleOf(::PrivateLogger)
                singleOf(::Housekeeping)
                singleOf(::WebSyncManager) bind RequestSync::class
                single { WebSyncManagerProvider { get() } }
                single { createTimeChanged(get()) }
                single {
                    LibPebble3(
                        get(),
                        get(),
                        get(),
                        get(),
                        get(),
                        get(),
                        get(),
                        get(),
                        get(),
                        get(),
                        get(),
                        get(),
                        get(),
                        get(),
                        get(),
                        get(),
                        get(),
                        get(),
                        get(),
                        get(),
                        get(),
                        get(),
                        get()
                    )
                } bind LibPebble::class
                single { RealConnectionScopeFactory(koin) } bind ConnectionScopeFactory::class
                singleOf(::RealCreatePlatformIdentifier) bind CreatePlatformIdentifier::class
                singleOf(::GattServerManager)
                singleOf(::NotificationApi) bind NotificationApps::class
                singleOf(::RealBluetoothStateProvider) bind BluetoothStateProvider::class
                singleOf(::RealTimeProvider) bind TimeProvider::class
                singleOf(::DevConnectionServer)
                singleOf(::RealBlobDbDatabaseManager) bind BlobDbDatabaseManager::class
                single {
                    DevConnectionCloudpebbleProxy(
                        libPebble = get(),
                        url = "wss://cloudpebble-proxy.repebble.com/device",
                        scope = get(),
                        token = proxyTokenProvider
                    )
                }
                single { HttpClient() }
                factory { HackyProvider { get<Scanning>() } }
                factory<Clock> { Clock.System }
                factory<kotlin.time.Clock> { kotlin.time.Clock.System }
                singleOf(::BlobDbDaos)
                singleOf(::ActionOverrides)
                singleOf(::PhoneCalendarSyncer)
                singleOf(::MissedCallSyncer)
                singleOf(::FirmwareDownloader)
                singleOf(::JsTokenUtil)
                singleOf(::Datalogging)
                singleOf(::Health)
                singleOf(::ErrorTracker)
                singleOf(::RealConnectionFailureHandler) bind ConnectionFailureHandler::class
                singleOf(::PhoneContactsSyncer)
                singleOf(::ContactsApi) bind Contacts::class
                singleOf(::RealLibPebbleAnalytics) binds arrayOf(
                    LibPebbleAnalytics::class,
                    AnalyticsEvents::class
                )
                factory {
                    Json {
                        // Important that everything uses this - otherwise future additions to web apis will
                        // crash the app.
                        ignoreUnknownKeys = true
                    }
                }

                scope<ConnectionScope> {
                    // Params
                    scoped { get<ConnectionScopeProperties>().scope }
                    scoped { get<ConnectionScopeProperties>().identifier }
                    scoped { get<ConnectionScopeProperties>().identifier as PebbleBleIdentifier }
                    scoped { get<ConnectionScopeProperties>().identifier as PebbleSocketIdentifier }
                    scoped { get<ConnectionScopeProperties>().useBtClassic }
                    scoped { (get<ConnectionScopeProperties>().platformIdentifier as PlatformIdentifier.BlePlatformIdentifier).peripheral }

                    // Connection
                    scopedOf(::KableGattConnector)
                    scopedOf(::PebbleBle)
                    scopedOf(::PebbleBtClassic)
                    scopedOf(::RealConnectionAnalyticsLogger) bind ConnectionAnalyticsLogger::class
                    scoped<GattConnector> {
                        when (get<PebbleIdentifier>()) {
                            is PebbleBleIdentifier -> get<KableGattConnector>()
                            else -> TODO("not implemented")
                        }
                    }
                    scoped<TransportConnector> {
                        val identifier = get<PebbleIdentifier>()
                        val useBtClassic = get<UseBtClassicAddress>()
                        when (identifier) {
                            is PebbleBleIdentifier -> {
                                when {
                                    useBtClassic.address != null -> get<PebbleBtClassic>()
                                    else -> get<PebbleBle>()
                                }
                            }
                            else -> TODO("not implemented")
                        }
                    }
                    scoped {
                        // We ran out of helper function overloads with enough params...
                        RealPebbleConnector(
                            get(), get(), get(),
                            get(), get(), get(),
                            get(), get(), get(),
                            get(), get(), get(),
                            get(), get(), get(),
                            get(), get(), get(),
                            get(), get(), get(),
                            get(), get(), get(),
                            get(), get(), get(),
                            get(), get(), get(),
                        )
                    } bind PebbleConnector::class
                    scopedOf(::PebbleProtocolRunner)
                    scopedOf(::Negotiator)
                    scoped { PebbleProtocolStreams() }
                    scopedOf(::PPoG)
                    scoped { PPoGStream() }
                    scopedOf(::PpogClient)
                    scopedOf(::PpogServer)
                    scoped<PPoGPacketSender> {
                        when (get<BleConfigFlow>().value.reversedPPoG) {
                            true -> get<PpogClient>()
                            false -> get<PpogServer>()
                        }
                    }
                    scopedOf(::ConnectionParams)
                    scopedOf(::Mtu)
                    scopedOf(::ConnectivityWatcher)
                    scopedOf(::BatteryWatcher)
                    scopedOf(::PebblePairing)
                    scopedOf(::RealPebbleProtocolHandler) bind PebbleProtocolHandler::class
                    scopedOf(::PreConnectScanner)

                    // Services
                    scopedOf(::SystemService)
                    scopedOf(::AppRunStateService)
                    scopedOf(::PutBytesService)
                    scopedOf(::BlobDBService)
                    scopedOf(::AppFetchService)
                    scopedOf(::TimelineService)
                    scopedOf(::AppMessageService)
                    scopedOf(::DataLoggingService)
                    scopedOf(::LogDumpService)
                    scopedOf(::GetBytesService)
                    scopedOf(::PhoneControlService)
                    scopedOf(::MusicService)
                    scopedOf(::ScreenshotService)
                    scopedOf(::VoiceService)
                    scopedOf(::AudioStreamService)
                    scopedOf(::AppReorderService)

                    // Endpoint Managers
                    scopedOf(::PutBytesSession)
                    scoped { get<TransportConnector>().disconnected }
                    scopedOf(::RealFirmwareUpdater) bind FirmwareUpdater::class
                    scopedOf(::TimelineActionManager)
                    scopedOf(::AppFetchProvider)
                    scopedOf(::DebugPebbleProtocolSender)
                    scopedOf(::CompanionAppLifecycleManager)
                    scopedOf(::BlobDB)
                    scopedOf(::PhoneControlManager)
                    scopedOf(::MusicControlManager)
                    scopedOf(::AppOrderManager)
                    scopedOf(::LanguagePackInstaller)
                    scopedOf(::RealFirmwareUpdateManager) bind FirmwareUpdateManager::class
                    scoped {
                        DevConnectionManager(
                            transport = get<WatchConfigFlow>().flow.map {
                                if (it.watchConfig.lanDevConnection) {
                                    get<DevConnectionServer>()
                                } else {
                                    get<DevConnectionCloudpebbleProxy>()
                                }
                            },
                            identifier = get(),
                            protocolHandler = get(),
                            companionAppLifecycleManager = get(),
                            scope = get(),
                        )
                    }
                    scopedOf(::VoiceSessionManager)


                    // TODO we ccoouulllddd scope this further to inject more things that we still
                    //  pass in as args
                    //  - transport connected = has connected gatt client
                    //  - fully connected = has WatchInfo (more useful)
                }
            }
        )
    )
    return koin
}
