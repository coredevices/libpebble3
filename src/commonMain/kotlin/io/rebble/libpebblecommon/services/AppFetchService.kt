package io.rebble.libpebblecommon.services

import io.rebble.libpebblecommon.ProtocolHandler
import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.packets.AppFetchIncomingPacket
import io.rebble.libpebblecommon.packets.AppFetchOutgoingPacket
import io.rebble.libpebblecommon.protocolhelpers.PebblePacket
import io.rebble.libpebblecommon.protocolhelpers.ProtocolEndpoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel

class AppFetchService(private val protocolHandler: PebbleProtocolHandler) : ProtocolService {
    val receivedMessages = Channel<AppFetchIncomingPacket>(Channel.BUFFERED)

    fun init(scope: CoroutineScope) {
        scope.async {
            protocolHandler.inboundMessages.collect {
                if (it is AppFetchIncomingPacket) {
                    receivedMessages.trySend(it)
                }
            }
        }
    }

    suspend fun send(packet: AppFetchOutgoingPacket) {
        protocolHandler.send(packet)
    }
}