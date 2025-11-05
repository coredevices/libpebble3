package io.rebble.libpebblecommon.connection

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.LibPebbleAnalytics
import io.rebble.libpebblecommon.WatchConfigFlow
import io.rebble.libpebblecommon.connection.bt.BluetoothState
import io.rebble.libpebblecommon.connection.bt.BluetoothStateProvider
import io.rebble.libpebblecommon.connection.bt.ble.BlePlatformConfig
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdater.FirmwareUpdateStatus
import io.rebble.libpebblecommon.database.BlobDbDatabaseManager
import io.rebble.libpebblecommon.database.MillisecondInstant
import io.rebble.libpebblecommon.database.asMillisecond
import io.rebble.libpebblecommon.database.dao.KnownWatchDao
import io.rebble.libpebblecommon.database.entity.KnownWatchItem
import io.rebble.libpebblecommon.database.entity.identifier
import io.rebble.libpebblecommon.database.entity.type
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.di.ConnectionScope
import io.rebble.libpebblecommon.di.ConnectionScopeFactory
import io.rebble.libpebblecommon.di.ConnectionScopeProperties
import io.rebble.libpebblecommon.di.HackyProvider
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.di.UseBtClassicAddress
import io.rebble.libpebblecommon.di.logWatchEvent
import io.rebble.libpebblecommon.metadata.WatchColor
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import io.rebble.libpebblecommon.metadata.WatchType
import io.rebble.libpebblecommon.services.WatchInfo
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.runningReduce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** Everything that is persisted, not including fields that are duplicated elsewhere (e.g. goal) */
internal data class KnownWatchProperties(
    val name: String,
    val nickname: String?,
    val runningFwVersion: String,
    val serial: String,
    val lastConnected: MillisecondInstant?,
    val watchType: WatchHardwarePlatform,
    val color: WatchColor?,
    val btClassicMacAddress: String?,
)

internal fun WatchInfo.asWatchProperties(lastConnected: MillisecondInstant?, name: String, nickname: String?): KnownWatchProperties =
    KnownWatchProperties(
        name = name,
        nickname = nickname,
        runningFwVersion = runningFwVersion.stringVersion,
        serial = serial,
        lastConnected = lastConnected,
        watchType = platform,
        color = color,
        btClassicMacAddress = btAddress,
    )

private fun Watch.asKnownWatchItem(): KnownWatchItem? {
    if (knownWatchProps == null) return null
    return KnownWatchItem(
        transportIdentifier = identifier.asString,
        transportType = identifier.type(),
        name = name,
        nickname = nickname,
        runningFwVersion = knownWatchProps.runningFwVersion,
        serial = knownWatchProps.serial,
        connectGoal = connectGoal,
        lastConnected = knownWatchProps.lastConnected,
        watchType = knownWatchProps.watchType.revision,
        color = knownWatchProps.color,
        btClassicMacAddress = knownWatchProps.btClassicMacAddress,
    )
}

interface WatchConnector {
    fun addScanResult(scanResult: PebbleScanResult)
    fun requestConnection(identifier: PebbleIdentifier)
    fun requestDisconnection(identifier: PebbleIdentifier)
    fun clearScanResults()
    fun forget(identifier: PebbleIdentifier)
    fun setNickname(identifier: PebbleIdentifier, nickname: String?)
}

private data class Watch(
    val identifier: PebbleIdentifier,
    val name: String,
    val nickname: String?,
    /** Populated (and updated with fresh rssi etc) if recently discovered */
    val scanResult: PebbleScanResult?,
    val connectGoal: Boolean,
    /** Always populated if we have previously connected to this watch */
    val knownWatchProps: KnownWatchProperties?,
    /** Populated if there is an active connection */
    val activeConnection: ConnectionScope?,
    /**
     * What is currently persisted for this watch? Only used to check whether we need to persist
     * changes.
     */
    val asPersisted: KnownWatchItem?,
    val forget: Boolean,
    val firmwareUpdateAvailable: FirmwareUpdateCheckResult?,
    val lastFirmwareUpdateState: FirmwareUpdateStatus,
    val connectionFailureInfo: ConnectionFailureInfo?,
) {
    init {
        check(scanResult != null || knownWatchProps != null)
    }
}

private fun KnownWatchItem.asProps(): KnownWatchProperties = KnownWatchProperties(
    name = name,
    runningFwVersion = runningFwVersion,
    serial = serial,
    lastConnected = lastConnected,
    watchType = WatchHardwarePlatform.fromHWRevision(watchType),
    color = color,
    nickname = nickname,
    btClassicMacAddress = btClassicMacAddress,
)

private data class CombinedState(
    val watches: Map<PebbleIdentifier, Watch>,
    val active: Map<PebbleIdentifier, ActivePebbleState>,
    val previousActive: Map<PebbleIdentifier, ActivePebbleState>,
    val btstate: BluetoothState,
)

class WatchManager(
    private val knownWatchDao: KnownWatchDao,
    private val pebbleDeviceFactory: PebbleDeviceFactory,
    private val createPlatformIdentifier: CreatePlatformIdentifier,
    private val connectionScopeFactory: ConnectionScopeFactory,
    private val libPebbleCoroutineScope: LibPebbleCoroutineScope,
    private val bluetoothStateProvider: BluetoothStateProvider,
    private val scanning: HackyProvider<Scanning>,
    private val watchConfig: WatchConfigFlow,
    private val clock: Clock,
    private val blePlatformConfig: BlePlatformConfig,
    private val connectionFailureHandler: ConnectionFailureHandler,
    private val analytics: LibPebbleAnalytics,
    private val blobDbDatabaseManager: BlobDbDatabaseManager,
) : WatchConnector, Watches {
    private val logger = Logger.withTag("WatchManager")
    private val allWatches: MutableStateFlow<Map<PebbleIdentifier, Watch>> = MutableStateFlow(
        runBlocking {
            knownWatchDao.knownWatches().associate {
                val identifier = it.identifier()
                identifier to Watch(
                    identifier = identifier,
                    name = it.name,
                    scanResult = null,
                    connectGoal = it.connectGoal,
                    knownWatchProps = it.asProps(),
                    activeConnection = null,
                    asPersisted = it,
                    forget = false,
                    firmwareUpdateAvailable = null,
                    lastFirmwareUpdateState = FirmwareUpdateStatus.NotInProgress.Idle,
                    nickname = it.nickname,
                    connectionFailureInfo = null,
                )
            }
        }
    )
    private val _watches = MutableStateFlow<List<PebbleDevice>>(
        allWatches.value.map {
            it.value.createPebbleDevice(
                batteryLevel = null,
                btState = bluetoothStateProvider.state.value,
                state = null,
                firmwareUpdateState = FirmwareUpdateStatus.NotInProgress.Idle,
                usingBtClassic = false,
            )
        }
    )
    override val watches: StateFlow<List<PebbleDevice>> = _watches.asStateFlow()
    private val _connectionEvents = MutableSharedFlow<PebbleConnectionEvent>(extraBufferCapacity = 5)
    override val connectionEvents: Flow<PebbleConnectionEvent> = _connectionEvents.asSharedFlow()
    private val activeConnections = mutableSetOf<PebbleIdentifier>()
    private var connectionNum = 0
    private val timeInitialized = clock.now()

    override fun watchesDebugState(): String = "allWatches=${allWatches.value.entries.joinToString("\n")}\n" +
            "activeConnections=$activeConnections\n" +
            "btState=${bluetoothStateProvider.state.value}"

    override fun setNickname(
        identifier: PebbleIdentifier,
        nickname: String?,
    ) {
        libPebbleCoroutineScope.launch {
            updateWatch(identifier) {
                it.copy(nickname = nickname)
            }
        }
    }

    private suspend fun persistIfNeeded(
        watch: Watch,
    ) {
        if (watch.forget) {
            logger.d("Deleting $watch from db")
            knownWatchDao.remove(watch.identifier.asString)
        } else {
            val wouldPersist = watch.asKnownWatchItem()
            if (wouldPersist != null && wouldPersist != watch.asPersisted) {
                knownWatchDao.insertOrUpdate(wouldPersist)
                updateWatch(watch.identifier) {
                    logger.d("Persisting changes for $wouldPersist")
                    it.copy(asPersisted = wouldPersist)
                }
            }
        }
    }

    private fun Watch.createPebbleDevice(
        batteryLevel: Int?,
        btState: BluetoothState,
        state: ConnectingPebbleState?,
        firmwareUpdateState: FirmwareUpdateStatus,
        usingBtClassic: Boolean,
    ): PebbleDevice =
        pebbleDeviceFactory.create(
            identifier = identifier,
            name = name,
            nickname = nickname,
            state = state,
            watchConnector = this@WatchManager,
            scanResult = scanResult,
            knownWatchProperties = knownWatchProps,
            connectGoal = connectGoal,
            firmwareUpdateAvailable = firmwareUpdateAvailable,
            firmwareUpdateState = firmwareUpdateState,
            bluetoothState = btState,
            lastFirmwareUpdateState = lastFirmwareUpdateState,
            batteryLevel = batteryLevel,
            connectionFailureInfo = connectionFailureInfo,
            usingBtClassic = usingBtClassic,
        )

    fun init() {
        logger.d("watchmanager init()")
        libPebbleCoroutineScope.launch {
            val activeConnectionStates = allWatches.flowOfAllDevices()
            combine(
                allWatches,
                activeConnectionStates,
                bluetoothStateProvider.state,
            ) { watches, active, btState ->
                CombinedState(watches, active, emptyMap(), btState)
            }.runningReduce { previous, current ->
                current.copy(previousActive = previous.active)
            }.mapNotNull { state ->
                // State can be null for the first scan emission
                val (watches, active, previousActive, btState) = state
                if (watchConfig.value.verboseWatchManagerLogging) {
                    logger.d { "combine: watches=$watches / active=$active / btstate=$btState / activeConnections=$activeConnections" }
                }
                // Update for active connection state
                watches.values.mapNotNull { device ->
                    val identifier = device.identifier
                    val states = CurrentAndPreviousState(
                        previousState = previousActive[identifier],
                        currentState = active[identifier],
                    )
                    val hasConnectionAttempt =
                        active.containsKey(device.identifier) || activeConnections.contains(device.identifier)

                    persistIfNeeded(device)
                    // Removed forgotten device once it is disconnected
                    if (!hasConnectionAttempt && device.forget) {
                        logger.d("removing ${device.identifier} from allWatches")
                        allWatches.update { it.minus(device.identifier) }
                        blobDbDatabaseManager.deleteSyncRecordsForStaleDevices()
                        return@mapNotNull null
                    }

                    // Goals
                    if (device.connectGoal && !hasConnectionAttempt && btState.enabled()) {
                        if (watchConfig.value.multipleConnectedWatchesSupported) {
                            connectTo(device)
                        } else {
                            if (active.isEmpty() && activeConnections.isEmpty()) {
                                connectTo(device)
                            }
                        }
                    } else if (hasConnectionAttempt && !btState.enabled()) {
                        disconnectFrom(device.identifier)
                        device.activeConnection?.cleanup()
                    } else if (!device.connectGoal && hasConnectionAttempt) {
                        disconnectFrom(device.identifier)
                    }

                    val firmwareUpdateAvailable = active[identifier]?.firmwareUpdateAvailable
                    if (firmwareUpdateAvailable != device.firmwareUpdateAvailable) {
                        updateWatch(identifier) {
                            it.copy(firmwareUpdateAvailable = firmwareUpdateAvailable)
                        }
                    }

                    val pebbleDevice = device.createPebbleDevice(
                        batteryLevel = states.currentState?.batteryLevel,
                        btState = btState,
                        state = states.currentState?.connectingPebbleState,
                        firmwareUpdateState = states.currentState?.firmwareUpdateStatus ?: FirmwareUpdateStatus.NotInProgress.Idle,
                        usingBtClassic = device.activeConnection?.usingBtClassic == true,
                    )

                    // Update persisted props after connection
                    if (watchConfig.value.verboseWatchManagerLogging) {
                        logger.d { "states=$states" }
                    }
                    // Watch just connected
                    if (states.currentState?.connectingPebbleState is ConnectingPebbleState.Connected
                        && states.previousState?.connectingPebbleState !is ConnectingPebbleState.Connected) {
                        val newProps = states.currentState.connectingPebbleState.watchInfo.asWatchProperties(
                            lastConnected = clock.now().asMillisecond(),
                            name = device.name,
                            nickname = device.nickname,
                        )
                        if (newProps != device.knownWatchProps) {
                            updateWatch(identifier) {
                                it.copy(
                                    knownWatchProps = newProps,
                                    connectionFailureInfo = null,
                                )
                            }
                            if (newProps.btClassicMacAddress != null &&
                                device.knownWatchProps?.btClassicMacAddress == null &&
                                blePlatformConfig.supportsBtClassic &&
                                watchConfig.value.preferBtClassicV2 &&
                                identifier is PebbleBleIdentifier &&
                                newProps.color?.platform?.supportsBtClassic() == true)
                                {
                                logger.i { "Disconnecting from BLE so that we can connect using BT Classic" }
                                device.activeConnection?.pebbleConnector?.disconnect()
                            }
                        }

                        // Clear scan results after we connected to one of them
                        if (device.scanResult != null) {
                            clearScanResults()
                        }

                        val connectedDevice = pebbleDevice as? CommonConnectedDevice
                        if (connectedDevice == null) {
                            logger.w { "$pebbleDevice isn't a CommonConnectedDevice" }
                        } else {
                            _connectionEvents.emit(PebbleConnectionEvent.PebbleConnectedEvent(connectedDevice))
                        }
                    }
                    // Watch just disconnected
                    if (states.currentState?.connectingPebbleState !is ConnectingPebbleState.Connected
                        && states.previousState?.connectingPebbleState is ConnectingPebbleState.Connected) {
                        val lastFwupState = states.previousState.firmwareUpdateStatus
                        if (device.lastFirmwareUpdateState != lastFwupState) {
                            updateWatch(identifier) {
                                it.copy(lastFirmwareUpdateState = lastFwupState)
                            }
                        }

                        _connectionEvents.emit(PebbleConnectionEvent.PebbleDisconnectedEvent(identifier))
                    }

                    pebbleDevice
                }
            }.collect {
                _watches.value = it.also { logger.d("watches: ${it.joinToString(separator = "\n", prefix = "\n")}") }
            }
        }
    }

    override fun addScanResult(scanResult: PebbleScanResult) {
        logger.d("addScanResult: $scanResult")
        val identifier = scanResult.identifier
        allWatches.update { devices ->
            val mutableDevices = devices.toMutableMap()
            val existing = devices[identifier]
            if (existing == null) {
                mutableDevices.put(
                    identifier, Watch(
                        identifier = identifier,
                        name = scanResult.name,
                        scanResult = scanResult,
                        connectGoal = false,
                        knownWatchProps = null,
                        activeConnection = null,
                        asPersisted = null,
                        forget = false,
                        firmwareUpdateAvailable = null,
                        lastFirmwareUpdateState = FirmwareUpdateStatus.NotInProgress.Idle,
                        nickname = null,
                        connectionFailureInfo = null,
                    )
                )
            } else {
                mutableDevices.put(identifier, existing.copy(scanResult = scanResult))
            }
            mutableDevices
        }
    }

    /**
     * Update the list of known watches, mutating the specific watch, if it exists, and it the
     * mutation is not null.
     */
    private fun updateWatch(identifier: PebbleIdentifier, mutation: (Watch) -> Watch?) {
        allWatches.update { watches ->
            // Fallback just in case we're using BT classic
            val device = watches[identifier] ?: watches[identifier.asString.asPebbleBleIdentifier()]
            if (device == null) {
                logger.w { "couldn't mutate device $identifier - not found" }
                return@update watches
            }
            val mutated = mutation(device) ?: return@update watches
            watches.plus(identifier to mutated)
        }
    }

    override fun requestConnection(identifier: PebbleIdentifier) {
        libPebbleCoroutineScope.launch {
            logger.d("requestConnection: $identifier")
            val scanning = scanning.get()
            scanning.stopBleScan()
            scanning.stopClassicScan()
            allWatches.update { watches ->
                watches.mapValues { entry ->
                    if (entry.key == identifier) {
                        entry.value.copy(connectGoal = true)
                    } else {
                        if (watchConfig.value.multipleConnectedWatchesSupported) {
                            entry.value
                        } else {
                            entry.value.copy(connectGoal = false)
                        }
                    }
                }
            }
        }
    }

    override fun requestDisconnection(identifier: PebbleIdentifier) {
        logger.d("requestDisconnection: $identifier")
        updateWatch(identifier = identifier) { it.copy(connectGoal = false) }
    }

    private fun connectTo(device: Watch) {
        val identifier = device.identifier
        logger.d("connectTo: $identifier (activeConnections=$activeConnections)")
        if (device.activeConnection != null) {
            logger.w("Already connecting to $identifier")
            return
        }
        updateWatch(identifier = identifier) { watch ->
            val connectionExists = activeConnections.contains(identifier)
            if (connectionExists) {
                logger.e("Already connecting to $identifier (this is a bug)")
                return@updateWatch null
            }

            var caughtException = false
            val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
                logger.e(
                    "watchmanager caught exception for $identifier: $throwable",
                    throwable,
                )
                if (caughtException) {
                    return@CoroutineExceptionHandler
                }
                caughtException = true
                if (throwable is ConnectionException) {
                    device.updateFailureReason(throwable.reason)
                }
                val connection = allWatches.value[identifier]?.activeConnection
                connection?.let {
                    libPebbleCoroutineScope.launch {
                        connection.cleanup()
                    }
                }
            }
            val overrideBtClassicAddress = when {
                blePlatformConfig.supportsBtClassic && watchConfig.value.preferBtClassicV2 &&
                        identifier is PebbleBleIdentifier && watch.color().platform.supportsBtClassic() &&
                        watch.knownWatchProps?.btClassicMacAddress != null -> UseBtClassicAddress(watch.knownWatchProps.btClassicMacAddress)

                else -> UseBtClassicAddress(null)
            }
            if (overrideBtClassicAddress.address != null) {
                logger.i { "Connecting using BT Classic: $overrideBtClassicAddress" }
            }
            val platformIdentifier = createPlatformIdentifier.identifier(identifier, watch.name)
            if (platformIdentifier == null) {
                // Probably because it couldn't create the device (ios throws on an unknown peristed
                // uuid, so we'll need to scan for it using the name/serial?)...
                // ...but TODO revit this once have more error modes + are handling BT being disabled
                if (device.knownWatchProps != null) {
                    logger.w("removing known device: $identifier")
                    forget(identifier)
                }
                // hack force another connection
                updateWatch(identifier = identifier) { watch ->
                    watch.copy()
                }
                return@updateWatch null
            }

            activeConnections.add(identifier)
            val deviceIdString = identifier.asString
            val thisConnectionNum = connectionNum++
            val coroutineContext =
                SupervisorJob() + exceptionHandler + CoroutineName("con-$deviceIdString-$thisConnectionNum")
            val connectionScope = ConnectionCoroutineScope(coroutineContext)
            logger.v("transport.createConnector")
            val color = watch.color()
            val connectionKoinScope = connectionScopeFactory.createScope(
                ConnectionScopeProperties(
                    identifier,
                    connectionScope,
                    platformIdentifier,
                    color,
                    overrideBtClassicAddress,
                )
            )
            val pebbleConnector: PebbleConnector = connectionKoinScope.pebbleConnector

            connectionScope.launch {
                try {
                    if (blePlatformConfig.delayBleConnectionsAfterAppStart && (clock.now() - timeInitialized) < APP_START_WAIT_TO_CONNECT) {
                        logger.i("Device connecting too soon after init: delaying to make sure we were really disconnected")
                        delay(APP_START_WAIT_TO_CONNECT)
                    }
                    pebbleConnector.connect(
                        previouslyConnected = device.knownWatchProps != null,
                        lastError = device.connectionFailureInfo?.reason,
                    )
                    logger.d("watchmanager connected (or failed..); waiting for disconnect: $identifier")
                    pebbleConnector.disconnected.disconnected.await()
                    // TODO if not know (i.e. if only a scanresult), then don't reconnect (set goal = false)
                    logger.d("watchmanager got disconnection: $identifier")
                    val connectionFailureReason = (pebbleConnector.state.value as? ConnectingPebbleState.Failed)?.reason
                    device.updateFailureReason(connectionFailureReason)
                } catch (e: Exception) {
                    // Because we call cleanup() in the `finally` block, the CoroutineExceptionHandler is not called.
                    // So catch it here just to log it.
                    logger.e(e) { "connect crashed" }
                    throw e
                } finally {
                    connectionKoinScope.cleanup()
                }
            }
            watch.copy(activeConnection = connectionKoinScope)
        }
    }

    private fun Watch.updateFailureReason(newReason: ConnectionFailureReason?) {
        if (newReason != null) {
            val failureInfo = ConnectionFailureInfo(
                reason = newReason,
                times = if (connectionFailureInfo?.reason == newReason) {
                    connectionFailureInfo.times + 1
                } else {
                    1
                },
            )
            updateWatch(identifier) {
                it.copy(
                    connectionFailureInfo = failureInfo,
                )
            }
        }
    }

    private suspend fun ConnectionScope.cleanup() {
        // Always run in the global scope, so that no cleanup work dies when we kill the connection
        // scope.
        libPebbleCoroutineScope.async {
            if (!closed.compareAndSet(expectedValue = false, newValue = true)) {
                logger.w("$identifier: already done cleanup")
                return@async
            }
            logger.d("$identifier: cleanup")
            pebbleConnector.disconnect()
            try {
                // TODO can this break when BT gets disabled? we call this, it times out, ...
                withTimeout(DISCONNECT_TIMEOUT) {
                    logger.d("$identifier: cleanup: waiting for disconnection")
                    pebbleConnector.disconnected.disconnected.await()
                }
            } catch (e: TimeoutCancellationException) {
                logger.w("cleanup: timed out waiting for disconnection from $identifier")
            }
            logger.d("$identifier: cleanup: removing active device")
            logger.d("$identifier: cleanup: cancelling scope")
            close()
            // This is essentially a hack to work around the case where we disconnect+reconnect so
            // fast that the watch doesn't realize. Wait a little bit before trying to connect
            // again
            if (blePlatformConfig.delayBleDisconnections) {
                logger.d { "delaying before marking as disconnected.." }
                delay(APP_START_WAIT_TO_CONNECT)
            }
            activeConnections.remove(identifier)
            updateWatch(identifier) { it.copy(activeConnection = null) }
        }.await()
    }

    private fun disconnectFrom(identifier: PebbleIdentifier) {
        logger.d("disconnectFrom: $identifier")
        val activeConnection = allWatches.value[identifier]?.activeConnection
        if (activeConnection == null) {
            Logger.d("disconnectFrom / not an active device")
            return
        }
        activeConnection.pebbleConnector.disconnect()
    }

    private fun Watch.isOnlyScanResult() =
        scanResult != null && activeConnection == null && !connectGoal && knownWatchProps == null

    override fun clearScanResults() {
        logger.d("clearScanResults")
        allWatches.update { aw ->
            aw.filterValues { watch ->
                !watch.isOnlyScanResult()
            }.mapValues {
                if (it.value.knownWatchProps != null) {
                    it.value.copy(scanResult = null)
                } else {
                    // Edge-case where we were disconnecting when this happened - don't leave an
                    // invalid totally empty watch record.
                    it.value
                }
            }
        }
    }

    override fun forget(identifier: PebbleIdentifier) {
        requestDisconnection(identifier)
        updateWatch(identifier) { it.copy(forget = true) }
    }

    private fun Watch.logAnalyticsEvent(name: String, props: Map<String, String>? = null) {
        analytics.logWatchEvent(color(), name, props)
    }

    companion object {
        private val DISCONNECT_TIMEOUT = 3.seconds
        private val APP_START_WAIT_TO_CONNECT = 2.5.seconds
    }
}

data class CurrentAndPreviousState(
    val previousState: ActivePebbleState?,
    val currentState: ActivePebbleState?,
)

data class ActivePebbleState(
    val connectingPebbleState: ConnectingPebbleState,
    val firmwareUpdateAvailable: FirmwareUpdateCheckResult?,
    val firmwareUpdateStatus: FirmwareUpdateStatus,
    val batteryLevel: Int?,
)

private fun StateFlow<Map<PebbleIdentifier, Watch>>.flowOfAllDevices(): Flow<Map<PebbleIdentifier, ActivePebbleState>> {
    return flatMapLatest { map ->
        val listOfInnerFlows: List<Flow<ActivePebbleState>> =
            map.values.mapNotNull { watchValue ->
                val connector = watchValue.activeConnection?.pebbleConnector
                val fwUpdateAvailableFlow =
                    watchValue.activeConnection?.firmwareUpdateManager?.availableUpdates ?: flowOf(null)
                val fwUpdateStatusFlow = watchValue.activeConnection?.firmwareUpdater?.firmwareUpdateState ?: flowOf(FirmwareUpdateStatus.NotInProgress.Idle)
                val batteryLevelFlow = watchValue.activeConnection?.batteryWatcher?.batteryLevel ?: flowOf(null)

                if (connector == null) {
                    null
                } else {
                    combine(connector.state, fwUpdateAvailableFlow, fwUpdateStatusFlow, batteryLevelFlow) { connectingState, fwUpdateAvailable, fwUpdateStatus, batteryLevel ->
                        ActivePebbleState(connectingState, fwUpdateAvailable, fwUpdateStatus, batteryLevel)
                    }
                }
            }
        if (listOfInnerFlows.isEmpty()) {
            flowOf(emptyMap())
        } else {
            combine(listOfInnerFlows) { innerValues ->
                innerValues.associateBy { it.connectingPebbleState.identifier }
            }
        }
    }
}

private fun Watch.color(): WatchColor = knownWatchProps?.color
    ?: scanResult?.leScanRecord?.extendedInfo?.color?.let { WatchColor.fromProtocolNumber(it.toInt()) }
    ?: WatchColor.Unknown

fun WatchType.supportsBtClassic(): Boolean = when (this) {
    WatchType.APLITE -> true
    WatchType.BASALT -> true
    WatchType.CHALK -> true
    WatchType.DIORITE -> false
    WatchType.EMERY -> false
    WatchType.FLINT -> false
}