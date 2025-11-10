package io.rebble.libpebblecommon.services

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.packets.ScreenshotData
import io.rebble.libpebblecommon.packets.ScreenshotHeader
import io.rebble.libpebblecommon.packets.ScreenshotRequest
import io.rebble.libpebblecommon.packets.ScreenshotResponseCode
import io.rebble.libpebblecommon.packets.ScreenshotVersion
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket.Companion.deserialize
import io.rebble.libpebblecommon.util.DataBuffer
import io.rebble.libpebblecommon.util.createImageBitmapFromPixelArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.timeout
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds

class ScreenshotService(
    private val protocolHandler: PebbleProtocolHandler,
    private val connectionCoroutineScope: ConnectionCoroutineScope,
) : ProtocolService, ConnectedPebble.Screenshot {
    private val logger = Logger.withTag("ScreenshotService")
    private val state = MutableStateFlow(ScreenshotState.Idle)

    override suspend fun takeScreenshot(): ImageBitmap? =
        withContext(connectionCoroutineScope.coroutineContext + Dispatchers.IO) {
            try {
                if (state.value == ScreenshotState.Busy) {
                    return@withContext null
                }
                state.value = ScreenshotState.Busy

                var header: ParsedScreenshotHeader? = null
                var data: DataBuffer? = null
                var finished = false

                /** Returns true if screenshot is incomplete */
                fun handleBytes(bytes: UByteArray): Boolean {
                    val buffer = data
                    if (buffer == null) {
                        throw IllegalStateException("buffer is null")
                    }
                    buffer.putBytes(bytes)
                    if (buffer.remaining == 0) {
                        finished = true
                    }
                    return !finished
                }

                // Screenshot packets are weird - they could be two different types of packet which
                // aren't distinguishable without knowing which one we're expecting
                protocolHandler.inboundMessages
                    .onSubscription {
                        protocolHandler.send(ScreenshotRequest())
                    }
                    .filterIsInstance<ScreenshotData>()
                    .takeWhile { packet ->
                        val bytes = packet.data.get()
                        if (header == null) {
                            val headerPacket = ScreenshotHeader()
                            deserialize(bytes, headerPacket)
                            val parsedHeader = headerPacket.parse()
                            header = parsedHeader
                            if (header.responseCode != ScreenshotResponseCode.OK) {
                                throw IllegalStateException("Screenshot response code was ${header.responseCode}")
                            }
                            logger.v { "header: $header" }
                            val bufferSize = (parsedHeader.height * parsedHeader.width) / parsedHeader.version.bitsPerPixel
                            data = DataBuffer(bufferSize)
                            handleBytes(headerPacket.data.get())
                        } else {
                            val dataPacket = ScreenshotData()
                            deserialize(bytes, dataPacket)
                            handleBytes(dataPacket.data.get())
                        }
                    }
                    .timeout(5.seconds)
                    .collect()

                logger.v { "done screenshot: finished = $finished" }
                val finalHeader = header
                if (finalHeader == null) {
                    throw IllegalStateException("null header at end?!")
                }
                if (finished) {
                    when (finalHeader.version) {
                        ScreenshotVersion.BLACK_WHITE_1_BIT -> {
                            val buffer = data ?: throw IllegalStateException("data buffer is null")
                            buffer.rewind()
                            val bytes = buffer.array()
                            val pixels = IntArray(finalHeader.width * finalHeader.height)
                            for (y in 0 until finalHeader.height) {
                                for (x in 0 until finalHeader.width) {
                                    val byteIndex = (y * (finalHeader.width / 8)) + (x / 8)
                                    val bitIndex = x % 8
                                    val bit = (bytes[byteIndex].toInt() shr bitIndex) and 1
                                    val index = (y * finalHeader.width) + x
                                    pixels[index] = if (bit == 0) Color.Black.toArgb() else Color.White.toArgb()
                                }
                            }
                            createImageBitmapFromPixelArray(pixels = pixels, width = finalHeader.width, height = finalHeader.height)
                        }
                        ScreenshotVersion.COLOR_8_BIT -> {
                            throw IllegalStateException("colour not supported yet")
                        }
                    }
                } else {
                    null
                }
            } catch (_: TimeoutCancellationException) {
                logger.w { "Timeout fetching screenshot" }
                null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(e) { "Error fetching screenshot: ${e.message}" }
                null
            } finally {
                state.value = ScreenshotState.Idle
            }
        }
}

fun ScreenshotHeader.parse(): ParsedScreenshotHeader = ParsedScreenshotHeader(
    responseCode = ScreenshotResponseCode.fromRawCode(responseCode.get()),
    version = ScreenshotVersion.fromRawCode(version.get()),
    height = height.get().toInt(),
    width = width.get().toInt(),
)

data class ParsedScreenshotHeader(
    val responseCode: ScreenshotResponseCode,
    val version: ScreenshotVersion,
    val height: Int,
    val width: Int,
)

enum class ScreenshotState {
    Idle,
    Busy,
}
