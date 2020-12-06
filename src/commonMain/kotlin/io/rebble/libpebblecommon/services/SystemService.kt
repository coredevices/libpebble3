package io.rebble.libpebblecommon.services

import io.rebble.libpebblecommon.PacketPriority
import io.rebble.libpebblecommon.ProtocolHandler
import io.rebble.libpebblecommon.getPlatform
import io.rebble.libpebblecommon.packets.*
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import io.rebble.libpebblecommon.protocolhelpers.ProtocolEndpoint
import io.rebble.libpebblecommon.structmapper.SInt
import io.rebble.libpebblecommon.structmapper.StructMapper
import io.rebble.libpebblecommon.util.DataBuffer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel

/**
 * Singleton to handle sending notifications cleanly, as well as TODO: receiving/acting on action events
 */
class SystemService(private val protocolHandler: ProtocolHandler) : ProtocolService {
    val receivedMessages = Channel<SystemPacket>(Channel.BUFFERED)

    private var watchVersionCallback: CompletableDeferred<WatchVersion.WatchVersionResponse>? = null
    private var watchModelCallback: CompletableDeferred<UByteArray>? = null

    init {
        protocolHandler.registerReceiveCallback(ProtocolEndpoint.PHONE_VERSION, this::receive)
        protocolHandler.registerReceiveCallback(ProtocolEndpoint.WATCH_VERSION, this::receive)
        protocolHandler.registerReceiveCallback(ProtocolEndpoint.FCT_REG, this::receive)
    }

    /**
     * Send an AppMessage
     */
    suspend fun send(packet: SystemPacket, priority: PacketPriority = PacketPriority.NORMAL) {
        protocolHandler.send(packet, priority)
    }

    suspend fun requestWatchVersion(): WatchVersion.WatchVersionResponse {
        val callback = CompletableDeferred<WatchVersion.WatchVersionResponse>()
        watchVersionCallback = callback

        send(WatchVersion.WatchVersionRequest())

        return callback.await()
    }

    suspend fun requestWatchModel(): Int {
        val callback = CompletableDeferred<UByteArray>()
        watchModelCallback = callback

        send(WatchFactoryData.WatchFactoryDataRequest("mfg_color"))

        val modelBytes = callback.await()

        return SInt(StructMapper()).also { it.fromBytes(DataBuffer(modelBytes)) }.get()
    }

    suspend fun receive(packet: PebblePacket) {
        if (packet !is SystemPacket) {
            throw IllegalStateException("Received invalid packet type: $packet")
        }

        when (packet) {
            is WatchVersion.WatchVersionResponse -> {
                watchVersionCallback?.complete(packet)
                watchVersionCallback = null
            }
            is WatchFactoryData.WatchFactoryDataResponse -> {
                watchModelCallback?.complete(packet.model.get())
                watchModelCallback = null
            }
            is WatchFactoryData.WatchFactoryDataError -> {
                watchModelCallback?.completeExceptionally(Exception("Failed to fetch watch model"))
                watchModelCallback = null
            }
            is PhoneAppVersion -> {
                val responsePacket = PhoneAppVersion.AppVersionResponse(
                    UInt.MAX_VALUE,

                    0u,
                    PhoneAppVersion.PlatformFlag.makeFlags(
                        getPlatform(), emptyList()
                    ),
                    2u,
                    4u,
                    4u,
                    2u,
                    ProtocolCapsFlag.makeFlags(
                        listOf(
                            ProtocolCapsFlag.Supports8kAppMessage
                        )
                    )

                )

                protocolHandler.send(responsePacket)
            }
            else -> receivedMessages.offer(packet)
        }
    }

}