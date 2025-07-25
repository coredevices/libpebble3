package io.rebble.libpebblecommon.connection

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.WatchConfigFlow
import io.rebble.libpebblecommon.connection.bt.BluetoothState
import io.rebble.libpebblecommon.connection.bt.BluetoothStateProvider
import io.rebble.libpebblecommon.connection.bt.ble.BlePlatformConfig
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdater.FirmwareUpdateStatus
import io.rebble.libpebblecommon.database.MillisecondInstant
import io.rebble.libpebblecommon.database.asMillisecond
import io.rebble.libpebblecommon.database.dao.KnownWatchDao
import io.rebble.libpebblecommon.database.entity.KnownWatchItem
import io.rebble.libpebblecommon.database.entity.transport
import io.rebble.libpebblecommon.database.entity.type
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.di.ConnectionScope
import io.rebble.libpebblecommon.di.ConnectionScopeFactory
import io.rebble.libpebblecommon.di.ConnectionScopeProperties
import io.rebble.libpebblecommon.di.HackyProvider
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlin.time.Duration.Companion.seconds

/** Everything that is persisted, not including fields that are duplicated elsewhere (e.g. goal) */
internal data class KnownWatchProperties(
    val name: String,
    val runningFwVersion: String,
    val serial: String,
    val lastConnected: MillisecondInstant?,
    val watchType: WatchHardwarePlatform,
)

internal fun WatchInfo.asWatchProperties(transport: Transport, lastConnected: MillisecondInstant?): KnownWatchProperties =
    KnownWatchProperties(
        name = transport.name,
        runningFwVersion = runningFwVersion.stringVersion,
        serial = serial,
        lastConnected = lastConnected,
        watchType = platform,
    )

private fun Watch.asKnownWatchItem(): KnownWatchItem? {
    if (knownWatchProps == null) return null
    return KnownWatchItem(
        transportIdentifier = transport.identifier.asString,
        transportType = transport.type(),
        name = transport.name,
        runningFwVersion = knownWatchProps.runningFwVersion,
        serial = knownWatchProps.serial,
        connectGoal = connectGoal,
        lastConnected = knownWatchProps.lastConnected,
        watchType = knownWatchProps.watchType.revision,
    )
}

interface WatchConnector {
    fun addScanResult(scanResult: PebbleScanResult)
    fun requestConnection(transport: Transport, uiContext: UIContext?)
    fun requestDisconnection(transport: Transport)
    fun clearScanResults()
    fun forget(transport: Transport)
}

private data class Watch(
    val transport: Transport,
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
    // TODO can add non-persisted state here e.g. previous connection failures to manage backoff etc
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
    watchType = WatchHardwarePlatform.fromHWRevision(watchType)
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
    private val companionDevice: CompanionDevice,
    private val scanning: HackyProvider<Scanning>,
    private val watchConfig: WatchConfigFlow,
    private val clock: Clock,
    private val blePlatformConfig: BlePlatformConfig,
) : WatchConnector, Watches {
    private val logger = Logger.withTag("WatchManager")
    private val allWatches = MutableStateFlow<Map<PebbleIdentifier, Watch>>(emptyMap())
    private val _watches = MutableStateFlow<List<PebbleDevice>>(emptyList())
    override val watches: StateFlow<List<PebbleDevice>> = _watches.asStateFlow()
    private val _connectionEvents = MutableSharedFlow<PebbleConnectionEvent>(extraBufferCapacity = 5)
    override val connectionEvents: Flow<PebbleConnectionEvent> = _connectionEvents.asSharedFlow()
    private val activeConnections = mutableSetOf<Transport>()
    private var connectionNum = 0
    private val timeInitialized = clock.now()

    override fun watchesDebugState(): String = "allWatches=${allWatches.value.entries.joinToString("\n")}\n" +
            "activeConnections=$activeConnections\n" +
            "btState=${bluetoothStateProvider.state.value}"

    private suspend fun loadKnownWatchesFromDb() {
        allWatches.value = knownWatchDao.knownWatches().associate {
            it.transport().identifier to Watch(
                transport = it.transport(),
                scanResult = null,
                connectGoal = it.connectGoal,
                knownWatchProps = it.asProps(),
                activeConnection = null,
                asPersisted = it,
                forget = false,
                firmwareUpdateAvailable = null,
                lastFirmwareUpdateState = FirmwareUpdateStatus.NotInProgress.Idle,
            )
        }
    }

    private suspend fun persistIfNeeded(
        watch: Watch,
    ) {
        if (watch.forget) {
            logger.d("Deleting $watch from db")
            knownWatchDao.remove(watch.transport)
        } else {
            val wouldPersist = watch.asKnownWatchItem()
            if (wouldPersist != null && wouldPersist != watch.asPersisted) {
                knownWatchDao.insertOrUpdate(wouldPersist)
                updateWatch(watch.transport) {
                    logger.d("Persisting changes for $wouldPersist")
                    it.copy(asPersisted = wouldPersist)
                }
            }
        }
    }

    fun init() {
        logger.d("watchmanager init()")
        libPebbleCoroutineScope.launch {
            loadKnownWatchesFromDb()
            val activeConnectionStates = allWatches.flowOfAllDevices()
            combine(
                allWatches,
                activeConnectionStates,
                bluetoothStateProvider.state,
            ) { watches, active, btstate ->
                CombinedState(watches, active, emptyMap(), btstate)
            }.scan(null as CombinedState?) { previous, current ->
                current.copy(previousActive = previous?.active ?: emptyMap())
            }.map { state ->
                // State can be null for the first scan emission
                val (watches, active, previousActive, btstate) = state ?: return@map emptyList()
                logger.v { "combine: watches=$watches / active=$active / btstate=$btstate / activeConnections=$activeConnections" }
                // Update for active connection state
                watches.values.mapNotNull { device ->
                    val transport = device.transport
                    val states = CurrentAndPreviousState(
                        previousState = previousActive[transport.identifier],
                        currentState = active[transport.identifier],
                    )
                    val hasConnectionAttempt =
                        active.containsKey(device.transport.identifier) || activeConnections.contains(device.transport)

                    persistIfNeeded(device)
                    // Removed forgotten device once it is disconnected
                    if (!hasConnectionAttempt && device.forget) {
                        logger.d("removing ${device.transport} from allWatches")
                        allWatches.update { it.minus(device.transport.identifier) }
                        return@mapNotNull null
                    }

                    if (device.connectGoal && !hasConnectionAttempt && btstate.enabled()) {
                        if (watchConfig.value.multipleConnectedWatchesSupported) {
                            connectTo(device)
                        } else {
                            if (active.isEmpty() && activeConnections.isEmpty()) {
                                connectTo(device)
                            }
                        }
                    } else if (hasConnectionAttempt && !btstate.enabled()) {
                        disconnectFrom(device.transport)
                        device.activeConnection?.cleanup()
                    } else if (!device.connectGoal && hasConnectionAttempt) {
                        disconnectFrom(device.transport)
                    }

                    val firmwareUpdateAvailable = active[transport.identifier]?.firmwareUpdateAvailable
                    if (firmwareUpdateAvailable != device.firmwareUpdateAvailable) {
                        updateWatch(transport) {
                            it.copy(firmwareUpdateAvailable = firmwareUpdateAvailable)
                        }
                    }

                    val pebbleDevice = pebbleDeviceFactory.create(
                        transport = transport,
                        state = states.currentState?.connectingPebbleState,
                        watchConnector = this@WatchManager,
                        scanResult = device.scanResult,
                        knownWatchProperties = device.knownWatchProps,
                        connectGoal = device.connectGoal,
                        firmwareUpdateAvailable = device.firmwareUpdateAvailable,
                        firmwareUpdateState = states.currentState?.firmwareUpdateStatus ?: FirmwareUpdateStatus.NotInProgress.Idle,
                        bluetoothState = btstate,
                        lastFirmwareUpdateState = device.lastFirmwareUpdateState,
                        batteryLevel = states.currentState?.batteryLevel,
                    )

                    // Update persisted props after connection
                    logger.v { "states=$states" }
                    // Watch just connected
                    if (states.currentState?.connectingPebbleState is ConnectingPebbleState.Connected
                        && states.previousState?.connectingPebbleState !is ConnectingPebbleState.Connected) {
                        val newProps = states.currentState.connectingPebbleState.watchInfo.asWatchProperties(transport, clock.now().asMillisecond())
                        if (newProps != device.knownWatchProps) {
                            updateWatch(transport) {
                                it.copy(knownWatchProps = newProps)
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
                            updateWatch(transport) {
                                it.copy(lastFirmwareUpdateState = lastFwupState)
                            }
                        }

                        _connectionEvents.emit(PebbleConnectionEvent.PebbleDisconnectedEvent(device.transport))
                    }
                    pebbleDevice
                }
            }.collect {
                _watches.value = it.also { logger.v("watches: $it") }
            }
        }
    }

    override fun addScanResult(scanResult: PebbleScanResult) {
        logger.d("addScanResult: $scanResult")
        val transport = scanResult.transport
        allWatches.update { devices ->
            val mutableDevices = devices.toMutableMap()
            val existing = devices[transport.identifier]
            if (existing == null) {
                mutableDevices.put(
                    transport.identifier, Watch(
                        transport = transport,
                        scanResult = scanResult,
                        connectGoal = false,
                        knownWatchProps = null,
                        activeConnection = null,
                        asPersisted = null,
                        forget = false,
                        firmwareUpdateAvailable = null,
                        lastFirmwareUpdateState = FirmwareUpdateStatus.NotInProgress.Idle,
                    )
                )
            } else {
                mutableDevices.put(transport.identifier, existing.copy(scanResult = scanResult))
            }
            mutableDevices
        }
    }

    /**
     * Update the list of known watches, mutating the specific watch, if it exists, and it the
     * mutation is not null.
     */
    private fun updateWatch(transport: Transport, mutation: (Watch) -> Watch?) {
        allWatches.update { watches ->
            val device = watches[transport.identifier]
            if (device == null) {
                logger.w("couldn't mutate device $transport - not found")
                return@update watches
            }
            val mutated = mutation(device) ?: return@update watches
            watches.plus(transport.identifier to mutated)
        }
    }

    override fun requestConnection(transport: Transport, uiContext: UIContext?) {
        libPebbleCoroutineScope.launch {
            logger.d("requestConnection: $transport")
            val scanning = scanning.get()
            scanning.stopBleScan()
            scanning.stopClassicScan()
            val registered = companionDevice.registerDevice(transport, uiContext)
            if (!registered) {
                logger.w { "failed to register companion device; not connecting to $transport" }
                return@launch
            }
            allWatches.update { watches ->
                watches.mapValues { entry ->
                    if (entry.key == transport.identifier) {
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

    override fun requestDisconnection(transport: Transport) {
        logger.d("requestDisconnection: $transport")
        updateWatch(transport = transport) { it.copy(connectGoal = false) }
    }

    private fun connectTo(device: Watch) {
        val transport = device.transport
        logger.d("connectTo: $transport (activeConnections=$activeConnections)")
        // TODO I think there is a still a race here, where we can wind up connecting multiple
        //  times to the same watch, because the Flow wasn't updated yet
        if (device.activeConnection != null) {
            logger.w("Already connecting to $transport")
            return
        }
        updateWatch(transport = device.transport) { watch ->
//            val connectionExists = allWatches.value[transport]?.activeConnection != null
            val connectionExists = activeConnections.contains(transport)
            if (connectionExists) {
                logger.e("Already connecting to $transport (this is a bug)")
                return@updateWatch null
            }

            var caughtException = false
            val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
                logger.e(
                    "watchmanager caught exception for $transport: $throwable",
                    throwable,
                )
                if (caughtException) {
                    return@CoroutineExceptionHandler
                }
                caughtException = true
                // TODO (not necessarily here but..) handle certain types of "fatal" disconnection (e.g.
                //  bad FW version) by not attempting to endlessly reconnect.
                val connection = allWatches.value[transport.identifier]?.activeConnection
                connection?.let {
                    libPebbleCoroutineScope.launch {
                        connection.cleanup()
                    }
                }
            }
            val platformIdentifier = createPlatformIdentifier.identifier(transport)
            if (platformIdentifier == null) {
                // Probably because it couldn't create the device (ios throws on an unknown peristed
                // uuid, so we'll need to scan for it using the name/serial?)...
                // ...but TODO revit this once have more error modes + are handling BT being disabled
                if (device.knownWatchProps != null) {
                    logger.w("removing known device: $transport")
                    forget(transport)
                }
                // hack force another connection
                updateWatch(transport = device.transport) { watch ->
                    watch.copy()
                }
                return@updateWatch null
            }

            activeConnections.add(transport)
            val deviceIdString = transport.identifier.asString
            val thisConnectionNum = connectionNum++
            val coroutineContext =
                SupervisorJob() + exceptionHandler + CoroutineName("con-$deviceIdString-$thisConnectionNum")
            val connectionScope = ConnectionCoroutineScope(coroutineContext)
            logger.v("transport.createConnector")
            val connectionKoinScope = connectionScopeFactory.createScope(
                ConnectionScopeProperties(
                    transport,
                    connectionScope,
                    platformIdentifier
                )
            )
            val pebbleConnector: PebbleConnector = connectionKoinScope.pebbleConnector

            connectionScope.launch {
                try {
                    if (blePlatformConfig.delayBleConnectionsAfterAppStart && (clock.now() - timeInitialized) < APP_START_WAIT_TO_CONNECT) {
                        logger.i("Device connecting too soon after init: delaying to make sure we were really disconnected")
                        delay(APP_START_WAIT_TO_CONNECT)
                    }
                    pebbleConnector.connect(device.knownWatchProps != null)
                    logger.d("watchmanager connected (or failed..); waiting for disconnect: $transport")
                    pebbleConnector.disconnected.disconnected.await()
                    // TODO if not know (i.e. if only a scanresult), then don't reconnect (set goal = false)
                    logger.d("watchmanager got disconnection: $transport")
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

    private suspend fun ConnectionScope.cleanup() {
        // Always run in the global scope, so that no cleanup work dies when we kill the connection
        // scope.
        libPebbleCoroutineScope.async {
            if (!closed.compareAndSet(expectedValue = false, newValue = true)) {
                logger.w("${transport}: already done cleanup")
                return@async
            }
            logger.d("${transport}: cleanup")
            pebbleConnector.disconnect()
            try {
                // TODO can this break when BT gets disabled? we call this, it times out, ...
                withTimeout(DISCONNECT_TIMEOUT) {
                    logger.d("${transport}: cleanup: waiting for disconnection")
                    pebbleConnector.disconnected.disconnected.await()
                }
            } catch (e: TimeoutCancellationException) {
                logger.w("cleanup: timed out waiting for disconnection from ${transport}")
            }
            logger.d("${transport}: cleanup: removing active device")
            logger.d("${transport}: cleanup: cancelling scope")
            close()
            // This is essentially a hack to work around the case where we disconnect+reconnect so
            // fast that the watch doesn't realize. Wait a little bit before trying to connect
            // again
            if (blePlatformConfig.delayBleDisconnections) {
                logger.d { "delaying before marking as disconnected.." }
                delay(APP_START_WAIT_TO_CONNECT)
            }
            activeConnections.remove(transport)
            updateWatch(transport) { it.copy(activeConnection = null) }
        }.await()
    }

    private fun disconnectFrom(transport: Transport) {
        logger.d("disconnectFrom: $transport")
        val activeConnection = allWatches.value[transport.identifier]?.activeConnection
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

    override fun forget(transport: Transport) {
        requestDisconnection(transport)
        updateWatch(transport) { it.copy(forget = true) }
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
                innerValues.associateBy { it.connectingPebbleState.transport.identifier }
            }
        }
    }
}

fun ConnectingPebbleState.Connected.firmwareUpdateState(): FirmwareUpdateStatus = when (this) {
    is ConnectingPebbleState.Connected.ConnectedInPrf -> this.services.firmware.firmwareUpdateState.value
    is ConnectingPebbleState.Connected.ConnectedNotInPrf -> this.services.firmware.firmwareUpdateState.value
}