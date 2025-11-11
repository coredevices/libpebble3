package io.rebble.libpebblecommon.connection.bt

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.IntentFilter
import android.os.Build
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.PebbleBleIdentifier
import io.rebble.libpebblecommon.connection.asPebbleBleIdentifier
import io.rebble.libpebblecommon.connection.bt.ble.pebble.ConnectivityStatus
import io.rebble.libpebblecommon.util.asFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull

actual fun isBonded(identifier: PebbleBleIdentifier): Boolean {
    @Suppress("DEPRECATION")
    val adapter = BluetoothAdapter.getDefaultAdapter()
    val device = adapter.getRemoteDevice(identifier.macAddress)
    try {
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            return true
        }
        // Sometimes getBondState() lies - check if it says false
        val bondedDevices = adapter.bondedDevices.toSet().filterNotNull()
        if (bondedDevices.any { it.address.asPebbleBleIdentifier() == identifier }) {
            return true
        }
    } catch (e: SecurityException) {
        Logger.e("error checking bond state")
    }
    return false
}

actual fun getBluetoothDevicePairEvents(
    context: AppContext,
    identifier: PebbleBleIdentifier,
    connectivity: Flow<ConnectivityStatus>,
): Flow<BluetoothDevicePairEvent> {
    return IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED).asFlow(context.context, exported = true)
        .mapNotNull {
            val device: BluetoothDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                it.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            } ?: return@mapNotNull null
            BluetoothDevicePairEvent(
                device = device.address.asPebbleBleIdentifier(),
                bondState = it.getIntExtra(
                    BluetoothDevice.EXTRA_BOND_STATE,
                    BluetoothDevice.BOND_NONE
                ),
                unbondReason = it.getIntExtra("android.bluetooth.device.extra.REASON", -1)
                    .takeIf { it != -1 }
            )
        }
        .filter {
            identifier == it.device
        }
}

actual fun createBond(identifier: PebbleBleIdentifier): Boolean {
    Logger.d("createBond()")
    @Suppress("DEPRECATION")
    val adapter = BluetoothAdapter.getDefaultAdapter()
    val macAddress = identifier.macAddress
    val device = adapter.getRemoteDevice(macAddress)
    return try {
        device.createBond()
    } catch (e: SecurityException) {
        Logger.e("failed to create bond", e)
        false
    }
}
