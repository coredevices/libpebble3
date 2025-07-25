package io.rebble.libpebblecommon.connection.bt.ble.pebble

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.UUIDs.PPOGATT_DEVICE_CHARACTERISTIC_READ
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.UUIDs.PPOGATT_DEVICE_CHARACTERISTIC_WRITE
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.UUIDs.PPOGATT_DEVICE_SERVICE_UUID_CLIENT
import io.rebble.libpebblecommon.connection.bt.ble.ppog.PPoGPacketSender
import io.rebble.libpebblecommon.connection.bt.ble.ppog.PPoGStream
import io.rebble.libpebblecommon.connection.bt.ble.transport.ConnectedGattClient
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattWriteType
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import kotlinx.coroutines.launch

class PpogClient(
    private val pPoGStream: PPoGStream,
    private val scope: ConnectionCoroutineScope,
) : PPoGPacketSender {
    private lateinit var gattClient: ConnectedGattClient

    suspend fun init(client: ConnectedGattClient) {
        Logger.d("PpogClient init()")
        gattClient = client
        val flow = gattClient.subscribeToCharacteristic(
            serviceUuid = PPOGATT_DEVICE_SERVICE_UUID_CLIENT,
            characteristicUuid = PPOGATT_DEVICE_CHARACTERISTIC_READ,
        )
        if (flow == null) {
            Logger.e("error subscribing to reverse data characteristic")
            return
        }
        Logger.d("PpogClient subscribed")
        scope.launch {
            flow.collect {
//                Logger.d("PpogClient inbound... ${it.joinToString()}")
                pPoGStream.inboundPPoGBytesChannel.send(it)
            }
        }
    }

    override suspend fun sendPacket(packet: ByteArray): Boolean {
        return gattClient.writeCharacteristic(
            serviceUuid = PPOGATT_DEVICE_SERVICE_UUID_CLIENT,
            characteristicUuid = PPOGATT_DEVICE_CHARACTERISTIC_READ,
            value = packet,
            writeType = GattWriteType.NoResponse,
        )
    }

    override fun wasRestoredWithSubscribedCentral(): Boolean = false
}