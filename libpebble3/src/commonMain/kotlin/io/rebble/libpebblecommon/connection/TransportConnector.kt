package io.rebble.libpebblecommon.connection

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.WatchConfigFlow
import io.rebble.libpebblecommon.connection.ConnectingPebbleState.Connected
import io.rebble.libpebblecommon.connection.ConnectingPebbleState.Connecting
import io.rebble.libpebblecommon.connection.ConnectingPebbleState.Failed
import io.rebble.libpebblecommon.connection.ConnectingPebbleState.Inactive
import io.rebble.libpebblecommon.connection.ConnectingPebbleState.Negotiating
import io.rebble.libpebblecommon.connection.devconnection.DevConnectionManager
import io.rebble.libpebblecommon.connection.endpointmanager.AppFetchProvider
import io.rebble.libpebblecommon.connection.endpointmanager.AppOrderManager
import io.rebble.libpebblecommon.connection.endpointmanager.DebugPebbleProtocolSender
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdater
import io.rebble.libpebblecommon.connection.endpointmanager.LanguagePackInstaller
import io.rebble.libpebblecommon.connection.endpointmanager.CompanionAppLifecycleManager
import io.rebble.libpebblecommon.connection.endpointmanager.audio.VoiceSessionManager
import io.rebble.libpebblecommon.connection.endpointmanager.blobdb.BlobDB
import io.rebble.libpebblecommon.connection.endpointmanager.musiccontrol.MusicControlManager
import io.rebble.libpebblecommon.connection.endpointmanager.phonecontrol.PhoneControlManager
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.TimelineActionManager
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.packets.ProtocolCapsFlag
import io.rebble.libpebblecommon.services.AppFetchService
import io.rebble.libpebblecommon.services.DataLoggingService
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.services.FirmwareVersion.Companion.slot
import io.rebble.libpebblecommon.services.GetBytesService
import io.rebble.libpebblecommon.services.LogDumpService
import io.rebble.libpebblecommon.services.MusicService
import io.rebble.libpebblecommon.services.PutBytesService
import io.rebble.libpebblecommon.services.ScreenshotService
import io.rebble.libpebblecommon.services.SystemService
import io.rebble.libpebblecommon.services.WatchInfo
import io.rebble.libpebblecommon.services.app.AppRunStateService
import io.rebble.libpebblecommon.services.appmessage.AppMessageService
import io.rebble.libpebblecommon.services.blobdb.BlobDBService
import io.rebble.libpebblecommon.web.FirmwareUpdateManager
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Instant.Companion.DISTANT_PAST

enum class ConnectionFailureReason {
    RegisterGattServer,
    FailedToConnect,
    ConnectTimeout,
    SubscribeConnectivity,
    ConnectionStatus,
    MtuGattError,
    NegotiationFailed,
    PeripheralAlreadyClosed,
    GattErrorUnknown,
    GattErrorUnknown147,
    GattInsufficientAuth,
    ReadPairingTrigger,
    CreateBondFailed,
    PairingTimedOut,
    NotAnError_NeverAttmpedConnection,
    TimeoutInitializingPpog,
    ClassicConnectionFailed,
}

sealed class PebbleConnectionResult {
    data object Success : PebbleConnectionResult()

    data class Failed(val reason: ConnectionFailureReason) : PebbleConnectionResult()
}

interface TransportConnector {
    suspend fun connect(lastError: ConnectionFailureReason?): PebbleConnectionResult
    suspend fun disconnect()
    val disconnected: Deferred<ConnectionFailureReason>
}

class WasDisconnected(val disconnected: Deferred<ConnectionFailureReason>)

sealed class ConnectingPebbleState {
    abstract val identifier: PebbleIdentifier

    data class Inactive(override val identifier: PebbleIdentifier) : ConnectingPebbleState()
    data class Connecting(override val identifier: PebbleIdentifier) : ConnectingPebbleState()
    data class Failed(override val identifier: PebbleIdentifier, val reason: ConnectionFailureReason) : ConnectingPebbleState()
    data class Negotiating(override val identifier: PebbleIdentifier) : ConnectingPebbleState()
    sealed class Connected : ConnectingPebbleState() {
        abstract val watchInfo: WatchInfo

        data class ConnectedInPrf(
            override val identifier: PebbleIdentifier,
            override val watchInfo: WatchInfo,
            val services: ConnectedPebble.PrfServices,
        ) : Connected()

        data class ConnectedNotInPrf(
            override val identifier: PebbleIdentifier,
            override val watchInfo: WatchInfo,
            val services: ConnectedPebble.Services,
        ) : Connected()
    }
}

fun ConnectingPebbleState?.isActive(): Boolean = when (this) {
    is Connected.ConnectedInPrf, is Connected.ConnectedNotInPrf, is Connecting, is Negotiating -> true
    is Failed, is Inactive, null -> false
}

interface PebbleConnector {
    suspend fun connect(previouslyConnected: Boolean, lastError: ConnectionFailureReason? = null)
    fun disconnect()
    val disconnected: WasDisconnected
    val state: StateFlow<ConnectingPebbleState>
}

class RealPebbleConnector(
    private val transportConnector: TransportConnector,
    private val identifier: PebbleIdentifier,
    private val scope: ConnectionCoroutineScope,
    private val negotiator: Negotiator,
    private val pebbleProtocolRunner: PebbleProtocolRunner,
    private val systemService: SystemService,
    private val appRunStateService: AppRunStateService,
    private val dataLoggingService: DataLoggingService,
    private val putBytesService: PutBytesService,
    private val firmwareUpdater: FirmwareUpdater,
    private val blobDBService: BlobDBService,
    private val appFetchService: AppFetchService,
    private val appMessageService: AppMessageService,
    private val timelineActionManager: TimelineActionManager,
    private val blobDB: BlobDB,
    private val companionAppLifecycleManager: CompanionAppLifecycleManager,
    private val appFetchProvider: AppFetchProvider,
    private val debugPebbleProtocolSender: DebugPebbleProtocolSender,
    private val logDumpService: LogDumpService,
    private val getBytesService: GetBytesService,
    private val phoneControlManager: PhoneControlManager,
    private val musicService: MusicService,
    private val musicControlManager: MusicControlManager,
    private val firmwareUpdateManager: FirmwareUpdateManager,
    private val devConnectionManager: DevConnectionManager,
    private val screenshotService: ScreenshotService,
    private val voiceSessionManager: VoiceSessionManager,
    private val watchConfig: WatchConfigFlow,
    private val appOrderManager: AppOrderManager,
    private val languagePackInstaller: LanguagePackInstaller,
) : PebbleConnector {
    private val logger = Logger.withTag("PebbleConnector-$identifier")
    private val _state = MutableStateFlow<ConnectingPebbleState>(Inactive(identifier))
    override val state: StateFlow<ConnectingPebbleState> = _state.asStateFlow()
    override val disconnected = WasDisconnected(transportConnector.disconnected)

    override suspend fun connect(previouslyConnected: Boolean, lastError: ConnectionFailureReason?) {
        _state.value = Connecting(identifier)

        val result = transportConnector.connect(lastError)
        when (result) {
            is PebbleConnectionResult.Failed -> {
                logger.e("failed to connect: $result")
                transportConnector.disconnect()
                _state.value = Failed(identifier, result.reason)
            }

            is PebbleConnectionResult.Success -> {
                logger.d("$result")
                val negotiationJob = scope.async {
                    doAfterConnection(previouslyConnected)
                }
                val disconnectedJob = scope.launch {
                    transportConnector.disconnected.await()
                    logger.w { "Disconnected during negotiation" }
                    negotiationJob.cancel()
                }
                disconnectedJob.cancel()
            }
        }
    }

    override fun disconnect() {
        scope.launch {
            transportConnector.disconnect()
        }
    }

    private suspend fun doAfterConnection(previouslyConnected: Boolean) {
        _state.value = Negotiating(identifier)
        scope.launch {
            pebbleProtocolRunner.run()
        }

        systemService.init()
        appRunStateService.init()
        dataLoggingService.initialInit()

        val watchInfo = negotiator.negotiate(systemService, appRunStateService)
        if (watchInfo == null) {
            logger.w("negotiation failed: disconnecting")
            transportConnector.disconnect()
            _state.value = Failed(identifier, ConnectionFailureReason.NegotiationFailed)
            return
        }

        putBytesService.init()
        firmwareUpdater.init(
            watchPlatform = watchInfo.platform,
            runningSlot = watchInfo.runningFwVersion.slot(),
        )
        firmwareUpdateManager.init(watchInfo)
        logDumpService.init(watchInfo.capabilities.contains(ProtocolCapsFlag.SupportsInfiniteLogDump))

        val ignoreMissingPrfOnThisDevice = watchConfig.value.ignoreMissingPrf
        val recoveryMode = when {
            watchInfo.runningFwVersion.isRecovery -> true.also {
                logger.i("PRF running; going into recovery mode")
            }

            !ignoreMissingPrfOnThisDevice && watchInfo.recoveryFwVersion == null -> true.also {
                logger.w("No recovery FW installed!!! going into recovery mode")
            }

            watchInfo.runningFwVersion < FW_3_0_0 -> true.also {
                logger.w("FW below v3.0 isn't supported; going into recovery mode")
            }

            else -> false
        }
        if (recoveryMode) {
            _state.value = Connected.ConnectedInPrf(
                identifier = identifier,
                watchInfo = watchInfo,
                services = ConnectedPebble.PrfServices(
                    firmware = firmwareUpdater,
                    logs = logDumpService,
                    coreDump = getBytesService,
                    devConnection = devConnectionManager
                ),
            )
            return
        }

        blobDBService.init()
        blobDB.init(
            watchType = watchInfo.platform.watchType,
            unfaithful = watchInfo.isUnfaithful,
            previouslyConnected = previouslyConnected,
            capabilities = watchInfo.capabilities,
        )
        appFetchService.init()
        timelineActionManager.init()
        appFetchProvider.init(watchInfo.platform.watchType)
        appMessageService.init()
        companionAppLifecycleManager.init(identifier, watchInfo)
        phoneControlManager.init()
        musicControlManager.init()
        voiceSessionManager.init()
        dataLoggingService.realInit(watchInfo)
        appOrderManager.init()

        _state.value = Connected.ConnectedNotInPrf(
            identifier = identifier,
            watchInfo = watchInfo,
            services = ConnectedPebble.Services(
                debug = systemService,
                appRunState = appRunStateService,
                firmware = firmwareUpdater,
                messages = debugPebbleProtocolSender,
                time = systemService,
                appMessages = appMessageService,
                logs = logDumpService,
                coreDump = getBytesService,
                music = musicService,
                pkjs = companionAppLifecycleManager,
                companionAppControl = companionAppLifecycleManager,
                devConnection = devConnectionManager,
                screenshot = screenshotService,
                language = languagePackInstaller,
            )
        )
    }
}

private val FW_3_0_0 = FirmwareVersion(
    stringVersion = "v3.0.0",
    timestamp = DISTANT_PAST,
    major = 3,
    minor = 0,
    patch = 0,
    suffix = null,
    gitHash = "",
    isRecovery = false,
    isSlot0 = false,
    isDualSlot = false,
)
