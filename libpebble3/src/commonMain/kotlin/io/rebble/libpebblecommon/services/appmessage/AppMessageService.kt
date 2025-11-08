package io.rebble.libpebblecommon.services.appmessage

import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.packets.AppCustomizationSetStockAppIconMessage
import io.rebble.libpebblecommon.packets.AppCustomizationSetStockAppTitleMessage
import io.rebble.libpebblecommon.packets.AppMessage
import io.rebble.libpebblecommon.packets.AppMessageTuple
import io.rebble.libpebblecommon.services.ProtocolService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

private const val APPMESSAGE_BUFFER_SIZE = 16

class AppMessageService(
    private val protocolHandler: PebbleProtocolHandler,
    private val scope: ConnectionCoroutineScope
) : ProtocolService, ConnectedPebble.AppMessages {
    private val receivedMessages = HashMap<Uuid, Channel<AppMessageData>>()
    override val transactionSequence: Iterator<UByte> = AppMessageTransactionSequence().iterator()
    private val mapAccessMutex = Mutex()
    private var appMessageCallback: CompletableDeferred<AppMessage>? = null

    fun init() {
        protocolHandler.inboundMessages.onEach {
            when (it) {
                is AppMessage.AppMessageACK -> {
                    appMessageCallback?.complete(it)
                    appMessageCallback = null
                }

                is AppMessage.AppMessageNACK -> {
                    appMessageCallback?.complete(it)
                    appMessageCallback = null
                }

                is AppMessage.AppMessagePush -> {
                    getReceivedMessagesChannel(it.uuid.get()).trySend(it.appMessageData())
                }
            }
        }.launchIn(scope)
    }

    /**
     * Send an AppMessage
     */
    override suspend fun sendAppMessage(appMessageData: AppMessageData): AppMessageResult {
        val callback = CompletableDeferred<AppMessage>()
        appMessageCallback = callback
        val appMessage = AppMessage.AppMessagePush(
            transactionId = appMessageData.transactionId.toUByte(),
            uuid = appMessageData.uuid,
            tuples = appMessageData.data.toAppMessageTuples()
        )
        protocolHandler.send(appMessage)
        return when (val result = callback.await()) {
            is AppMessage.AppMessageACK -> result.appMessageResult()
            is AppMessage.AppMessageNACK -> result.appMessageResult()
            else -> throw IllegalStateException("Unexpected result: $result")
        }
    }

    override suspend fun sendAppMessageResult(appMessageResult: AppMessageResult) {
        val appMessage = when (appMessageResult) {
            is AppMessageResult.ACK -> AppMessage.AppMessageACK(appMessageResult.transactionId.toUByte())
            is AppMessageResult.NACK -> AppMessage.AppMessageNACK(appMessageResult.transactionId.toUByte())
        }
        protocolHandler.send(appMessage)
    }

    suspend fun send(packet: AppCustomizationSetStockAppIconMessage) {
        protocolHandler.send(packet)
    }

    suspend fun send(packet: AppCustomizationSetStockAppTitleMessage) {
        protocolHandler.send(packet)
    }

    override fun inboundAppMessages(appUuid: Uuid): Flow<AppMessageData> {
        return suspend { getReceivedMessagesChannel(appUuid) }.asFlow().flatMapConcat { it.receiveAsFlow() }
    }

    private suspend fun getReceivedMessagesChannel(appUuid: Uuid): Channel<AppMessageData> {
        receivedMessages[appUuid]?.let { return it }

        return mapAccessMutex.withLock {
            receivedMessages.getOrPut(appUuid) { Channel(APPMESSAGE_BUFFER_SIZE) }
        }
    }
}

private fun Map<Int, Any>.toAppMessageTuples(): List<AppMessageTuple> {
    return map { (key, value) ->
        val k = key.toUInt()
        when (value) {
            is String -> AppMessageTuple.createString(k, value)
            is UByteArray -> AppMessageTuple.createUByteArray(k, value)
            is ByteArray -> AppMessageTuple.createUByteArray(k, value.asUByteArray())
            is Int -> AppMessageTuple.createInt(k, value)
            is UInt -> AppMessageTuple.createUInt(k, value)
            is Short -> AppMessageTuple.createShort(k, value)
            is UShort -> AppMessageTuple.createUShort(k, value)
            is Byte -> AppMessageTuple.createByte(k, value)
            is UByte -> AppMessageTuple.createUByte(k, value)
            is Boolean -> AppMessageTuple.createShort(k, if (value) 1 else 0)
            else -> throw IllegalArgumentException("Unsupported type: ${value::class.simpleName}")
        }
    }
}
typealias AppMessageDictionary = Map<Int, Any>

data class AppMessageData(
    val transactionId: UByte,
    val uuid: Uuid,
    val data: AppMessageDictionary
)

sealed class AppMessageResult(val transactionId: UByte) {
    class ACK(transactionId: UByte) : AppMessageResult(transactionId)
    class NACK(transactionId: UByte) : AppMessageResult(transactionId)
}

private fun AppMessage.AppMessagePush.appMessageData(): AppMessageData {
    return AppMessageData(
        transactionId = transactionId.get(),
        uuid = uuid.get(),
        data = dictionary.list.associate { it.key.get().toInt() to it.getTypedData() }
    )
}

private fun AppMessage.AppMessageACK.appMessageResult() = AppMessageResult.ACK(transactionId.get())
private fun AppMessage.AppMessageNACK.appMessageResult() = AppMessageResult.NACK(transactionId.get())
