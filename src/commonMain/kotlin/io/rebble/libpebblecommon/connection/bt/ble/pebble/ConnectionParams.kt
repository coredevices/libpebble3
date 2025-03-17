package io.rebble.libpebblecommon.connection.bt.ble.pebble

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.UUIDs.CONNECTION_PARAMETERS_CHARACTERISTIC
import io.rebble.libpebblecommon.connection.bt.ble.pebble.LEConstants.UUIDs.PAIRING_SERVICE_UUID
import io.rebble.libpebblecommon.connection.bt.ble.transport.ConnectedGattClient
import io.rebble.libpebblecommon.connection.bt.ble.transport.GattWriteType
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async

class ConnectionParams(private val gattClient: ConnectedGattClient) {
    suspend fun subscribeAndConfigure(): Boolean {
        // TODO scope this
        val sub = gattClient.subscribeToCharacteristic(PAIRING_SERVICE_UUID, CONNECTION_PARAMETERS_CHARACTERISTIC)
        if (sub == null) {
            Logger.e("error subscribing to connection params")
            return false
        }
        GlobalScope.async {
            sub.collect {
                Logger.d("connection params changed: ${it.joinToString()}")
            }
        }
        val value = byteArrayOf(0, 1)
        return gattClient.writeCharacteristic(PAIRING_SERVICE_UUID, CONNECTION_PARAMETERS_CHARACTERISTIC, value, GattWriteType.WithResponse)
    }
}