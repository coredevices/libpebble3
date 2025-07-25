package io.rebble.libpebblecommon.connection.bt.ble.transport

import io.rebble.libpebblecommon.connection.BleDiscoveredPebbleDevice
import io.rebble.libpebblecommon.connection.BleScanResult
import io.rebble.libpebblecommon.connection.WatchManager
import io.rebble.libpebblecommon.connection.bt.ble.transport.impl.kableBleScanner
import kotlinx.coroutines.flow.Flow

//expect fun libpebbleBleScanner(): BleScanner

fun bleScanner(): BleScanner
 = kableBleScanner()
// = libpebbleBleScanner()

interface BleScanner {
    fun scan(namePrefix: String?): Flow<BleScanResult>
}
