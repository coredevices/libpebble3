package io.rebble.libpebblecommon.connection.bt

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.IntentFilter
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.Transport.BluetoothTransport
import io.rebble.libpebblecommon.util.asFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull

actual fun isBonded(transport: BluetoothTransport): Boolean {
    val adapter = BluetoothAdapter.getDefaultAdapter()
    val macAddress = transport.identifier.macAddress
    val device = adapter.getRemoteDevice(macAddress)
    try {
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            return true
        }
        // Sometimes getBondState() lies - check if it says false
        val bondedDevices = adapter.bondedDevices.toSet().filterNotNull()
        if (bondedDevices.any { macAddress.equals(it.address, ignoreCase = true) }) {
            return true
        }
    } catch (e: SecurityException) {
        Logger.e("error checking bond state")
    }
    // TODO null or something?
    return false
}

actual fun getBluetoothDevicePairEvents(context: AppContext, transport: BluetoothTransport): Flow<BluetoothDevicePairEvent> {
    return IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED).asFlow(context.context)
        .mapNotNull {
            val device = it.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                ?: return@mapNotNull null
            BluetoothDevicePairEvent(
                address = device.address,
                bondState = it.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE),
                unbondReason = it.getIntExtra("android.bluetooth.device.extra.REASON", -1).takeIf { it != -1 }
            )
        }
        .filter {
            transport.identifier.isEqualTo(it.address)
        }
}

actual fun createBond(transport: BluetoothTransport): Boolean {
    Logger.d("createBond()")
    val adapter = BluetoothAdapter.getDefaultAdapter()
    val macAddress = transport.identifier.macAddress
    val device = adapter.getRemoteDevice(macAddress)
    return try {
        device.createBond()
    } catch (e: SecurityException) {
        Logger.e("failed to create bond", e)
        false
    }
}
