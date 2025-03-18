package io.rebble.libpebblecommon.connection

import android.bluetooth.BluetoothDevice

actual class PebbleBluetoothIdentifier(
    val macAddress: String,
) {
    actual fun asString(): String = macAddress

    fun isEqualTo(device: BluetoothDevice) = device.address.equals(macAddress, ignoreCase = true)
    fun isEqualTo(macAddress: String) = macAddress.equals(macAddress, ignoreCase = true)
}