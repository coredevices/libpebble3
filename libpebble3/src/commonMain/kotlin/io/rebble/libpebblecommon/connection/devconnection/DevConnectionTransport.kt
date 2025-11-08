package io.rebble.libpebblecommon.connection.devconnection

import co.touchlab.kermit.Logger
import io.ktor.utils.io.CancellationException
import io.ktor.utils.io.core.writeFully
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.PebbleIdentifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

internal expect fun getTempPbwPath(): Path

abstract class DevConnectionTransport(private val libPebble: LibPebble) {
    companion object {
        private val logger = Logger.withTag("DevConnectionTransport")
    }
    /** @param identifier Identifier of the device to connect to
     * @param inboundPKJSLogs Flow of PKJS logs from the device
     * @param inboundDeviceMessages Flow of messages received from the device, or null if the device disconnected
     * @param outboundDeviceMessages Collector for messages to send to the device
     */
    abstract suspend fun start(
        identifier: PebbleIdentifier,
        inboundPKJSLogs: Flow<String>,
        inboundDeviceMessages: Flow<ByteArray>,
        outboundDeviceMessages: suspend (ByteArray) -> Unit
    )
    abstract fun stop()

    protected suspend fun installPBW(payload: ByteArray): Boolean {
        try {
            val path = getTempPbwPath()

            withContext(Dispatchers.IO) {
                SystemFileSystem.sink(path).buffered().use {
                    it.writeFully(payload)
                }
            }
            return libPebble.sideloadApp(path)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(e) { "Failed to save/install bundle: ${e.message}" }
            return false
        }
    }
}
