@file:OptIn(ExperimentalUuidApi::class)

package io.rebble.libpebblecommon.connection.bt.ble.transport

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt.GATT_SUCCESS
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothGattService.SERVICE_TYPE_PRIMARY
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.Transport
import io.rebble.libpebblecommon.connection.Transport.BluetoothTransport.BleTransport
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

private fun getService(appContext: AppContext): BluetoothManager? =
    appContext.context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

actual fun openGattServer(appContext: AppContext): GattServer? {
    return try {
        val callback = GattServerCallback()
        getService(appContext)?.openGattServer(appContext.context, callback)?.let {
            callback.server = it
            io.rebble.libpebblecommon.connection.bt.ble.transport.GattServer(it, callback)
        }
    } catch (e: SecurityException) {
        Logger.d("error opening gatt server", e)
        null
    }
}

data class RegisteredDevice(
    val dataChannel: SendChannel<ByteArray>,
    val device: BluetoothDevice,
    val notificationsEnabled: Boolean,
)

class GattServerCallback : BluetoothGattServerCallback() {
    private val _connectionState = MutableStateFlow<ServerConnectionstateChanged?>(null)
    val connectionState = _connectionState.asSharedFlow()
    val registeredDevices: MutableMap<String, RegisteredDevice> = mutableMapOf()
    var server: BluetoothGattServer? = null

    override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
        Logger.d("onConnectionStateChange: ${device.address} = $newState")
        _connectionState.tryEmit(
            ServerConnectionstateChanged(
                deviceId = device.address,
                connectionState = newState,
            )
        )
    }

    // TODO shouldn't really be a stateFlow, but I can't get SharedFlow to work. Just use a channel?
    private val _serviceAdded = MutableStateFlow<ServerServiceAdded?>(null)
    val serviceAdded = _serviceAdded.asSharedFlow()

    override fun onServiceAdded(status: Int, service: BluetoothGattService) {
        Logger.d("onServiceAdded: ${service.uuid}")
        val res = _serviceAdded.tryEmit(ServerServiceAdded(service.uuid.toString()))
    }

    private val _characteristicReadRequest = MutableStateFlow<RawCharacteristicReadRequest?>(null)
    val characteristicReadRequest = _characteristicReadRequest.asSharedFlow()

    data class RawCharacteristicReadRequest(
        val device: BluetoothDevice,
        val requestId: Int,
        val offset: Int,
        val characteristic: BluetoothGattCharacteristic,
        // TODO Hack to make it emit again while using a StateFlow, because MutableSharedFlow is not
        //  working for some reason
        val uuidHack: Uuid = Uuid.random(),
    )

    override fun onCharacteristicReadRequest(
        device: BluetoothDevice,
        requestId: Int,
        offset: Int,
        characteristic: BluetoothGattCharacteristic,
    ) {
        Logger.d("onCharacteristicReadRequest: ${characteristic.uuid}")
        _characteristicReadRequest.tryEmit(
            RawCharacteristicReadRequest(device, requestId, offset, characteristic)
        )
    }

//    private val _characteristicWriteRequest =
//        MutableStateFlow<ServerCharacteristicWriteRequest?>(null)
//    val characteristicWriteRequest = _characteristicWriteRequest.asSharedFlow()

    override fun onCharacteristicWriteRequest(
        device: BluetoothDevice,
        requestId: Int,
        characteristic: BluetoothGattCharacteristic,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray,
    ) {
//        Logger.d("onCharacteristicWriteRequest: ${device.address} / ${characteristic.uuid}: ${value.joinToString()}")
        val registeredDevice = registeredDevices[device.address]
        if (registeredDevice == null) {
            Logger.e("onCharacteristicWriteRequest couldn't find registered device: ${device.address}")
            return
        }
        val result = registeredDevice.dataChannel.trySend(value)
        if (result.isFailure) {
            Logger.e("onCharacteristicWriteRequest error writing to channel: $result")
        }
    }

    override fun onDescriptorWriteRequest(
        device: BluetoothDevice,
        requestId: Int,
        descriptor: BluetoothGattDescriptor,
        preparedWrite: Boolean,
        responseNeeded: Boolean,
        offset: Int,
        value: ByteArray?,
    ) {
        Logger.d("onDescriptorWriteRequest: ${device.address} / ${descriptor.characteristic.uuid}")
        val registeredDevice = registeredDevices[device.address]
        if (registeredDevice == null) {
            Logger.e("onDescriptorWriteRequest device not registered!")
            return
        }
        val gattServer = server
        if (gattServer == null) {
            Logger.e("onDescriptorWriteRequest no server!!")
            return
        }
        if (!gattServer.sendResponse(device, requestId, GATT_SUCCESS, offset, value)) {
            Logger.e("onDescriptorWriteRequest failed to respond")
            return
        }
        registeredDevices[device.address] = registeredDevice.copy(notificationsEnabled = true)
//        Logger.d("/onDescriptorWriteRequest")
    }

    val notificationSent = MutableStateFlow<NotificationSent?>(null)

    override fun onNotificationSent(device: BluetoothDevice, status: Int) {
//        Logger.d("onNotificationSent: ${device.address}")
        notificationSent.tryEmit(NotificationSent(device.address, status))
    }
}

actual class GattServer(
    val server: BluetoothGattServer,
    val callback: GattServerCallback,
    val cbTimeout: Long = 8000,
) : BluetoothGattServerCallback() {
    actual val characteristicReadRequest = callback.characteristicReadRequest.filterNotNull().map {
        ServerCharacteristicReadRequest(
            deviceId = it.device.address,
            uuid = it.characteristic.uuid.toString(),
            respond = { bytes ->
                try {
                    server.sendResponse(
                        it.device,
                        it.requestId,
                        GATT_SUCCESS,
                        it.offset,
                        bytes
                    )
                } catch (e: SecurityException) {
                    Logger.d("error sending read response", e)
                    false
                }
            },
        )
    }

    actual val connectionState = callback.connectionState.filterNotNull()

    actual suspend fun closeServer() {
        try {
            server.clearServices()
        } catch (e: SecurityException) {
            Logger.d("error clearing gatt services", e)
        }
        try {
            server.close()
        } catch (e: SecurityException) {
            Logger.d("error closing gatt server", e)
        }
    }

    actual suspend fun addServices(services: List<GattService>) {
        Logger.d("addServices: $services")
        services.forEach { addService(it) }
        Logger.d("/addServices")
    }

    private suspend fun addService(service: GattService) {
        Logger.d("addService: ${service.uuid}")
        try {
            callback.serviceAdded.onSubscription {
                server.addService(service.asAndroidService())
            }.first {
                val equals = service.uuid.equals(it?.uuid, ignoreCase = true)
                Logger.d("// first = '${it?.uuid}'/'${service.uuid}' : equals = $equals")
                equals
            }
        } catch (e: SecurityException) {
            Logger.d("error adding gatt service ${service.uuid}", e)
        }
    }

    actual fun registerDevice(
        transport: BleTransport,
        sendChannel: SendChannel<ByteArray>
    ) {
        Logger.d("registerDevice: $transport")
        val adapter = BluetoothAdapter.getDefaultAdapter()
        val bluetoothDevice = adapter.getRemoteDevice(transport.identifier.macAddress)
        callback.registeredDevices[transport.identifier.macAddress] =
            RegisteredDevice(
                dataChannel = sendChannel,
                device = bluetoothDevice,
                notificationsEnabled = false,
            )
    }

    actual fun unregisterDevice(transport: BleTransport) {
        callback.registeredDevices.remove(transport.identifier.macAddress)
    }

    actual suspend fun sendData(
        transport: BleTransport,
        serviceUuid: String,
        characteristicUuid: String,
        data: ByteArray,
    ): Boolean {
        val registeredDevice = callback.registeredDevices[transport.identifier.macAddress]
        if (registeredDevice == null) {
            Logger.e("sendData: couldn't find registered device: $transport")
            return false
        }
        val service = server.getService(UUID.fromString(serviceUuid))
        if (service == null) {
            Logger.e("sendData: couldn't find service")
            return false
        }
        val characteristic = service.getCharacteristic(UUID.fromString(characteristicUuid))
        if (characteristic == null) {
            Logger.e("sendData: couldn't find characteristic")
            return false
        }
        callback.notificationSent.value = null // TODO better way of doing this?
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            val writeRes = server.notifyCharacteristicChanged(registeredDevice.device, characteristic, false, data)
            if (writeRes != BluetoothStatusCodes.SUCCESS) {
                Logger.e("couldn't notify data characteristic: $writeRes")
                return false
            }
        } else {
            characteristic.value = data
            if (!server.notifyCharacteristicChanged(registeredDevice.device, characteristic, false)) {
                Logger.e("couldn't notify data characteristic")
                return false
            }
        }
        return try {
            val res = withTimeout(cbTimeout) {
                callback.notificationSent.filterNotNull().first { transport.identifier.isEqualTo(it.deviceId) }
            }
            if (res.status != GATT_SUCCESS) {
                Logger.e("characteristic notify error: ${res.status}")
                false
            } else {
                true
            }
        } catch (e: TimeoutCancellationException) {
            Logger.e("characteristic notify timed out")
            false
        }
    }
}

private fun GattService.asAndroidService(): BluetoothGattService {
    val service = BluetoothGattService(UUID.fromString(uuid), SERVICE_TYPE_PRIMARY)
    characteristics.forEach { c ->
        val characteristic = c.asBluetoothGattcharacteristic()
        service.addCharacteristic(characteristic)
    }
    return service
}

private fun GattCharacteristic.asBluetoothGattcharacteristic() = BluetoothGattCharacteristic(
    /* uuid = */ UUID.fromString(uuid),
    /* properties = */ properties,
    /* permissions = */ permissions,
).apply {
    this@asBluetoothGattcharacteristic.descriptors.forEach { d ->
        val descriptor = BluetoothGattDescriptor(
            /* uuid = */ UUID.fromString(d.uuid),
            /* permissions = */ d.permissions,
        )
        addDescriptor(descriptor)
    }
}

private fun List<Int>.or(): Int = reduceOrNull { a, b -> a or b } ?: 0
