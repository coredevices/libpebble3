package io.rebble.libpebblecommon.connection

import io.rebble.libpebblecommon.services.appmessage.AppMessageData
import io.rebble.libpebblecommon.services.appmessage.AppMessageResult
import io.rebble.libpebblecommon.services.appmessage.AppMessageTransactionSequence
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.consumeAsFlow

class FakeAppMessages: ConnectedPebble.AppMessages {
    val inbound = Channel<AppMessageData>(Channel.RENDEZVOUS)
    val outbound = Channel<AppMessageData>(Channel.RENDEZVOUS, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val outboundResults = Channel<AppMessageResult>(Channel.RENDEZVOUS, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    override val inboundAppMessages: Flow<AppMessageData> = inbound.consumeAsFlow()

    override suspend fun sendAppMessage(appMessageData: AppMessageData): AppMessageResult {
        return if (outbound.trySend(appMessageData).isSuccess) {
            AppMessageResult.ACK(appMessageData.transactionId)
        } else if (outbound.isClosedForSend) {
            AppMessageResult.NACK(appMessageData.transactionId)
        } else {
            AppMessageResult.ACK(appMessageData.transactionId)
        }
    }

    override suspend fun sendAppMessageResult(appMessageResult: AppMessageResult) {
        outboundResults.send(appMessageResult)
    }

    override val transactionSequence: Iterator<UByte> = AppMessageTransactionSequence().iterator()
}