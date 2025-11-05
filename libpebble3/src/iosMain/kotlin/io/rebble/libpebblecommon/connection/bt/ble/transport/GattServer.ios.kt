package io.rebble.libpebblecommon.connection.bt.ble.transport

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.BleConfigFlow
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.PebbleBleIdentifier
import io.rebble.libpebblecommon.connection.asPebbleBleIdentifier
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.UUIDs.FAKE_SERVICE_UUID
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.UUIDs.META_CHARACTERISTIC_SERVER
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.UUIDs.PPOGATT_DEVICE_CHARACTERISTIC_SERVER
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.UUIDs.PPOGATT_DEVICE_SERVICE_UUID_SERVER
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import platform.CoreBluetooth.CBATTErrorRequestNotSupported
import platform.CoreBluetooth.CBATTErrorSuccess
import platform.CoreBluetooth.CBATTRequest
import platform.CoreBluetooth.CBAttributePermissionsReadable
import platform.CoreBluetooth.CBAttributePermissionsWriteable
import platform.CoreBluetooth.CBCentral
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicPropertyNotify
import platform.CoreBluetooth.CBCharacteristicPropertyRead
import platform.CoreBluetooth.CBCharacteristicPropertyWriteWithoutResponse
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBManagerStateUnknown
import platform.CoreBluetooth.CBMutableCharacteristic
import platform.CoreBluetooth.CBMutableService
import platform.CoreBluetooth.CBPeripheralManager
import platform.CoreBluetooth.CBPeripheralManagerDelegateProtocol
import platform.CoreBluetooth.CBPeripheralManagerOptionRestoreIdentifierKey
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.darwin.NSObject
import platform.posix.memcpy
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

data class RegisteredDevice(
    val dataChannel: SendChannel<ByteArray>,
    val device: PebbleBleIdentifier,
    val notificationsEnabled: Boolean,
)

fun Uuid.asCbUuid(): CBUUID = CBUUID.UUIDWithString(toString())

private fun CBUUID.asUuid(): Uuid = Uuid.parse(UUIDString())

fun NSUUID.asUuid(): Uuid = Uuid.parse(UUIDString())

private fun NSData.toByteArray(): ByteArray = ByteArray(length.toInt()).apply {
    if (length > 0u) {
        usePinned {
            memcpy(it.addressOf(0), bytes, length)
        }
    }
}

private fun ByteArray.toNSData(): NSData = memScoped {
    NSData.create(
        bytes = allocArrayOf(this@toNSData),
        length = size.convert(),
    )
}

actual class GattServer(
    private val bleConfigFlow: BleConfigFlow,
) : NSObject(), CBPeripheralManagerDelegateProtocol {
    private val logger = Logger.withTag("GattServer")
    private val peripheralManager: CBPeripheralManager = CBPeripheralManager(
        delegate = this,
        queue = null,
        options = mapOf(CBPeripheralManagerOptionRestoreIdentifierKey to "ppog-server"),
    )
    private val registeredDevices: MutableMap<PebbleBleIdentifier, RegisteredDevice> =
        mutableMapOf()
    private val registeredServices = mutableMapOf<Uuid, Map<Uuid, CBMutableCharacteristic>>()
    private var wasSubscribedAtRestore = false

    private fun verboseLog(message: () -> String) {
        if (bleConfigFlow.value.verbosePpogLogging) {
            logger.v(message = message)
        }
    }

    override fun peripheralManager(
        peripheral: CBPeripheralManager,
        willRestoreState: Map<Any?, *>
    ) {
        val restoredServices = willRestoreState["kCBRestoredServices"] as? List<CBMutableService>
        restoredServices?.forEach { service ->
            val characteristics = buildMap {
                val characteristics = service.characteristics as? List<CBMutableCharacteristic>
                characteristics?.forEach { c ->
                    if (c.subscribedCentrals?.isNotEmpty() ?: false) {
                        logger.d { "${c.UUID} was subscribed at restore time" }
                        wasSubscribedAtRestore = true
                    }
                    put(c.UUID.asUuid(), c)
                }
            }
            registeredServices.put(service.UUID.asUuid(), characteristics)
        }
        logger.d { "restoredServices" }
    }

    private fun findCharacteristic(
        serviceUuid: Uuid,
        characteristicUuid: Uuid
    ): CBMutableCharacteristic? =
        registeredServices[serviceUuid]?.get(characteristicUuid)

    actual suspend fun addServices() {
        logger.d("addServices: waiting for power on")
        peripheralManagerState.first { it == CBManagerStatePoweredOn }
        addService(
            PPOGATT_DEVICE_SERVICE_UUID_SERVER,
            listOf(
                CBMutableCharacteristic(
                    type = META_CHARACTERISTIC_SERVER.asCbUuid(),
                    properties = CBCharacteristicPropertyRead,
                    value = null,
                    permissions = CBAttributePermissionsReadable,// CBAttributePermissionsReadEncryptionRequired,
                ),
                CBMutableCharacteristic(
                    type = PPOGATT_DEVICE_CHARACTERISTIC_SERVER.asCbUuid(),
                    properties = CBCharacteristicPropertyWriteWithoutResponse or CBCharacteristicPropertyNotify,
                    value = null,
                    permissions = CBAttributePermissionsWriteable// CBAttributePermissionsWriteEncryptionRequired,
                ),
            ),
        )
        addService(
            FAKE_SERVICE_UUID,
            listOf(
                CBMutableCharacteristic(
                    type = FAKE_SERVICE_UUID.asCbUuid(),
                    properties = CBCharacteristicPropertyRead,
                    value = null,
                    permissions = CBAttributePermissionsReadable, //CBAttributePermissionsReadEncryptionRequired,
                ),
            ),
        )
    }

    private suspend fun addService(
        serviceUuid: Uuid,
        characteristics: List<CBMutableCharacteristic>,
    ) {
        if (findCharacteristic(serviceUuid, characteristics.first().UUID.asUuid()) != null) {
            logger.d { "service $serviceUuid already present!" }
            return
        }
        logger.d("addService: $serviceUuid")
        val service = CBMutableService(type = serviceUuid.asCbUuid(), primary = true)
        service.setCharacteristics(characteristics)
        serviceAdded.onSubscription {
            peripheralManager.addService(service)
        }.first { it.uuid == serviceUuid }
        registeredServices[serviceUuid] = characteristics.associateBy { it.UUID.asUuid() }
        logger.d("/addService: $serviceUuid")
    }

    actual suspend fun closeServer() {
    }

    private val _characteristicReadRequest = MutableSharedFlow<ServerCharacteristicReadRequest>()
    actual val characteristicReadRequest: Flow<ServerCharacteristicReadRequest> =
        _characteristicReadRequest.asSharedFlow()

    actual fun registerDevice(
        identifier: PebbleBleIdentifier,
        sendChannel: SendChannel<ByteArray>,
    ) {
        logger.d("registerDevice: $identifier")
        registeredDevices[identifier] =
            RegisteredDevice(
                dataChannel = sendChannel,
                device = identifier,
                notificationsEnabled = false,
            )
    }

    actual fun unregisterDevice(identifier: PebbleBleIdentifier) {
        registeredDevices.remove(identifier)
    }

    actual suspend fun sendData(
        identifier: PebbleBleIdentifier,
        serviceUuid: Uuid,
        characteristicUuid: Uuid,
        data: ByteArray,
    ): Boolean {
        val cbCharacteristic = findCharacteristic(
            serviceUuid = serviceUuid,
            characteristicUuid = characteristicUuid,
        )
        if (cbCharacteristic == null) {
            logger.w("sendData: couldn't find characteristic for $serviceUuid / $characteristicUuid")
            return false
        }
        val central = (cbCharacteristic.subscribedCentrals as? List<CBCentral>)?.firstOrNull {
            it.identifier.asUuid().toString().asPebbleBleIdentifier() == identifier
        }
        if (central == null) {
            logger.w("sendData: couldn't find central for $identifier")
            return false
        }
        return try {
            withTimeout(SEND_TIMEOUT) {
                while (true) {
                    if (peripheralManager.updateValue(
                            value = data.toNSData(),
                            forCharacteristic = cbCharacteristic,
                            onSubscribedCentrals = listOf(central),
                        )
                    ) {
                        return@withTimeout true
                    }
                    // Write did not succeed; wait for queue to drain
                    peripheralManagerReady.first()
                }
                false
            }
        } catch (e: TimeoutCancellationException) {
            logger.e { "timeout sending" }
            false
        }
    }

    actual fun wasRestoredWithSubscribedCentral(): Boolean {
        return wasSubscribedAtRestore
    }

    private val peripheralManagerState = MutableStateFlow(CBManagerStateUnknown)

    override fun peripheralManagerDidUpdateState(peripheral: CBPeripheralManager) {
        logger.d("peripheralManagerDidUpdateState: ${peripheral.state}")
        if (peripheral.state == CBManagerStatePoweredOn) {
            peripheralManagerState.value = CBManagerStatePoweredOn
        }
    }

    override fun peripheralManager(
        peripheral: CBPeripheralManager,
        didReceiveWriteRequests: List<*>,
    ) {
        didReceiveWriteRequests.mapNotNull { it as? CBATTRequest }.forEach { request ->
            verboseLog { "writeRequest: ${request.characteristic.UUID} / ${request.value}" }
            val identifier = request.central.identifier.asUuid().toString().asPebbleBleIdentifier()
            val device = registeredDevices[identifier]
            if (device == null) {
                logger.w("write request for unknown device: $identifier")
                peripheralManager.respondToRequest(request, CBATTErrorRequestNotSupported)
                return@forEach
            }
            val value = request.value
            if (value == null) {
                logger.w("write request with null value: $identifier")
                peripheralManager.respondToRequest(request, CBATTErrorRequestNotSupported)
                return@forEach
            }
            device.dataChannel.trySend(value.toByteArray())
            peripheralManager.respondToRequest(request, CBATTErrorSuccess)
        }
    }

    @ObjCSignatureOverride
    override fun peripheralManager(
        peripheral: CBPeripheralManager,
        central: CBCentral,
        didSubscribeToCharacteristic: CBCharacteristic,
    ) {
        val identifier = central.identifier.asUuid().toString().asPebbleBleIdentifier()
        logger.d("didSubscribeToCharacteristic: device=$identifier cuuid=${didSubscribeToCharacteristic.UUID}")
        val device = registeredDevices[identifier] ?: return
        registeredDevices[identifier] = device.copy(notificationsEnabled = true)
    }

    @ObjCSignatureOverride
    override fun peripheralManager(
        peripheral: CBPeripheralManager,
        central: CBCentral,
        didUnsubscribeFromCharacteristic: CBCharacteristic,
    ) {
        logger.d("didUnsubscribeFromCharacteristic")
        wasSubscribedAtRestore = false
    }

    private val serviceAdded = MutableSharedFlow<ServerServiceAdded>()

    override fun peripheralManager(
        peripheral: CBPeripheralManager,
        didAddService: CBService,
        error: NSError?,
    ) {
        logger.d("didAddService error=$error")
        runBlocking {
            serviceAdded.emit(ServerServiceAdded(didAddService.UUID.asUuid()))
        }
    }

    override fun peripheralManager(
        peripheral: CBPeripheralManager,
        didReceiveReadRequest: CBATTRequest,
    ) {
        logger.d("didReceiveReadRequest for ${didReceiveReadRequest.characteristic.UUID}")
        runBlocking {
            _characteristicReadRequest.emit(
                ServerCharacteristicReadRequest(
                    deviceId = didReceiveReadRequest.central.identifier.asUuid().toString()
                        .asPebbleBleIdentifier(),
                    uuid = didReceiveReadRequest.characteristic.UUID.asUuid(),
                    respond = { bytes ->
                        didReceiveReadRequest.setValue(bytes.toNSData())
                        peripheralManager.respondToRequest(didReceiveReadRequest, CBATTErrorSuccess)
                        true
                    },
                )
            )
        }
    }

    private val peripheralManagerReady = MutableSharedFlow<Unit>()

    override fun peripheralManagerIsReadyToUpdateSubscribers(peripheral: CBPeripheralManager) {
        verboseLog { "peripheralManagerIsReadyToUpdateSubscribers" }
        runBlocking {
            peripheralManagerReady.emit(Unit)
        }
    }
}

private val SEND_TIMEOUT = 8.seconds


actual fun openGattServer(appContext: AppContext, bleConfigFlow: BleConfigFlow): GattServer? {
    return GattServer(bleConfigFlow)
}