package io.rebble.libpebblecommon.io.rebble.libpebblecommon.connection.bt.classic.transport

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import co.touchlab.kermit.Logger
import io.ktor.utils.io.writeByteArray
import io.rebble.libpebblecommon.connection.ConnectionFailureReason
import io.rebble.libpebblecommon.connection.PebbleProtocolStreams
import io.rebble.libpebblecommon.connection.asPebbleBleIdentifier
import io.rebble.libpebblecommon.connection.bt.ble.pebble.PebblePairing
import io.rebble.libpebblecommon.connection.bt.classic.pebble.BtClassicConnector
import io.rebble.libpebblecommon.connection.bt.classic.pebble.ClassicConnectionResult
import io.rebble.libpebblecommon.connection.bt.isBonded
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.di.UseBtClassicAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class AndroidBtClassicConnector(
    private val btClassicAddress: UseBtClassicAddress,
    private val pairing: PebblePairing,
    private val pebbleProtocolStreams: PebbleProtocolStreams,
    private val connectionCoroutineScope: ConnectionCoroutineScope,
) : BtClassicConnector {
    private val logger = Logger.withTag("AndroidBtClassicConnector")
    private val _disconnected = CompletableDeferred<ConnectionFailureReason>()
    private var socket: BluetoothSocket? = null

    override suspend fun connect(): ClassicConnectionResult {
        if (btClassicAddress.address == null) {
            logger.e { "connect: btClassicAddress is null" }
            return ClassicConnectionResult.Failure
        }
        val identifier = btClassicAddress.address.asPebbleBleIdentifier()
        if (!isBonded(identifier)) {
            val pairingResult = pairing.requestClassicPairing(identifier)
            if (pairingResult != null) {
                return ClassicConnectionResult.Failure
            }
        }
        val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice(btClassicAddress.address)
        val btSocket = device.createRfcommSocketToServiceRecord(SERIAL_UUID)
        if (btSocket == null) {
            logger.w { "btSocket is null" }
            return ClassicConnectionResult.Failure
        }
        socket = btSocket
        btSocket.connect()

        connectionCoroutineScope.launch(Dispatchers.IO) {
            val data = ByteArray(500)
            while (true) {
                val bytes = btSocket.inputStream.read(data)
                if (bytes < 0) {
                    throw IllegalStateException("Couldn't read from stream")
                }
                if (bytes > 0) {
                    pebbleProtocolStreams.inboundPPBytes.writeByteArray(data.copyOf(bytes))
                    pebbleProtocolStreams.inboundPPBytes.flush()
                }
            }
        }
        connectionCoroutineScope.launch {
            pebbleProtocolStreams.outboundPPBytes.consumeAsFlow().collect { bytes ->
                withContext(Dispatchers.IO) {
                    btSocket.outputStream.write(bytes)
                    btSocket.outputStream.flush()
                }
            }
        }
        return ClassicConnectionResult.Success
    }

    override suspend fun disconnect() {
        _disconnected.complete(ConnectionFailureReason.ClassicConnectionFailed)
        socket?.close()
    }

    override val disconnected: Deferred<ConnectionFailureReason> = _disconnected

    companion object {
        private val SERIAL_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}