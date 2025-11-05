package io.rebble.libpebblecommon.pebblekit

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.CompanionApp
import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.di.LibPebbleKoinComponent
import io.rebble.libpebblecommon.js.CompanionAppDevice
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import io.rebble.libpebblecommon.services.appmessage.AppMessageData
import io.rebble.libpebblecommon.services.appmessage.AppMessageDictionary
import io.rebble.libpebblecommon.services.appmessage.AppMessageResult
import io.rebble.libpebblecommon.util.asFlow
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

class PebbleKitClassic(
    private val device: CompanionAppDevice,
    val appInfo: PbwAppInfo,
) :
    LibPebbleKoinComponent, CompanionApp {
    companion object {
        private val logger = Logger.withTag(PebbleKitClassic::class.simpleName!!)
    }

    val uuid: Uuid by lazy { Uuid.parse(appInfo.uuid) }
    private var runningScope: CoroutineScope? = null

    private val context: Context = getKoin().get()

    private suspend fun replyNACK(id: UByte) {
        withTimeoutOrNull(1000) {
            device.sendAppMessageResult(AppMessageResult.ACK(id))
        }
    }

    private suspend fun replyACK(id: UByte) {
        withTimeoutOrNull(1000) {
            device.sendAppMessageResult(AppMessageResult.ACK(id))
        }
    }

    private fun launchIncomingAppMessageHandler(device: ConnectedPebble.AppMessages, scope: CoroutineScope) {
        device.inboundAppMessages(uuid).onEach { appMessageData ->
            logger.d { "Got inbound message" }

            val pebbleDictionary = appMessageData.data.toPebbleDictionary()
            val intent = Intent(INTENT_APP_RECEIVE).apply {
                putExtra(APP_UUID, appMessageData.uuid.toJavaUuid())
                putExtra(TRANSACTION_ID, appMessageData.transactionId.toInt())

                putExtra(MSG_DATA, pebbleDictionary.toJsonString())
            }

            // Regular broadcasts are sometimes delayed on Android 14+. Use ordered ones instead.
            // https://stackoverflow.com/questions/77842817/slow-intent-broadcast-delivery-on-android-14
            context.sendOrderedBroadcast(intent, null)
        }.catch {
            logger.e(it) { "Error receiving app message: ${it.message}" }
        }.launchIn(scope)
    }

    // TODO app start and stop intents

    private fun launchOutgoingAppMessageHandlers(device: ConnectedPebble.AppMessages, scope: CoroutineScope) {
        scope.launch {
            IntentFilter(INTENT_APP_ACK).asFlow(context, exported = true).collect { intent ->
                logger.d { "Got outbound ack" }
                val transactionId: Int = intent.getIntExtra(TRANSACTION_ID, 0)
                replyACK(transactionId.toUByte())
            }
        }

        scope.launch {
            IntentFilter(INTENT_APP_NACK).asFlow(context, exported = true).collect { intent ->
                logger.d { "Got outbound nack" }
                val transactionId: Int = intent.getIntExtra(TRANSACTION_ID, 0)
                replyNACK(transactionId.toUByte())
            }
        }

        scope.launch {
            IntentFilter(INTENT_APP_SEND).asFlow(context, exported = true).collect { intent ->
                logger.d { "Got outbound message" }
                val uuid = intent.getSerializableExtra(APP_UUID) as UUID? ?: return@collect
                val dictionary: PebbleDictionary = PebbleDictionary.fromJson(
                    intent.getStringExtra(MSG_DATA)
                )
                val transactionId: Int = intent.getIntExtra(TRANSACTION_ID, 0)

                val msg = AppMessageData(transactionId.toUByte(), uuid.toKotlinUuid(), dictionary.toAppMessageDict())
                val result = device.sendAppMessage(msg)
                logger.d { "Result from the app: $result" }
                val intentAction = when (result) {
                    is AppMessageResult.ACK -> INTENT_APP_RECEIVE_ACK
                    is AppMessageResult.NACK -> INTENT_APP_RECEIVE_NACK
                }

                val intent = Intent(intentAction).apply {
                    putExtra(TRANSACTION_ID, result.transactionId.toInt())
                }

                // Regular broadcasts are sometimes delayed on Android 14+. Use ordered ones instead.
                // https://stackoverflow.com/questions/77842817/slow-intent-broadcast-delivery-on-android-14
                context.sendOrderedBroadcast(intent, null)
            }
        }
    }

    override suspend fun start(connectionScope: CoroutineScope) {
        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            logger.e(throwable) { "Unhandled exception in PebbleKitClassic: ${throwable.message}" }
        }
        val scope = connectionScope + Job() + CoroutineName("PebbleKitClassic-$uuid") + exceptionHandler
        runningScope = scope
        launchIncomingAppMessageHandler(device, scope)
        launchOutgoingAppMessageHandlers(device, scope)
    }

    override suspend fun stop() {
        runningScope?.cancel()
    }
}

private fun PebbleDictionary.toAppMessageDict(): AppMessageDictionary {
    return tuples.mapValues {
        when (it.value.type) {
            PebbleTuple.TupleType.BYTES -> it.value.value as ByteArray
            PebbleTuple.TupleType.STRING -> it.value.value as String
            PebbleTuple.TupleType.UINT -> when (it.value.length) {
                1 -> (it.value.value as Number).toLong().toUByte()
                2 -> (it.value.value as Number).toLong().toUShort()
                4 -> (it.value.value as Number).toLong().toUInt()
                else -> error("Unknown UINT size ${it.value.length}")
            }

            PebbleTuple.TupleType.INT -> when (it.value.length) {
                1 -> (it.value.value as Number).toByte()
                2 -> (it.value.value as Number).toShort()
                4 -> (it.value.value as Number).toInt()
                else -> error("Unknown INT size ${it.value.length}")
            }
        }
    }
}

private fun AppMessageDictionary.toPebbleDictionary(): PebbleDictionary {
    val dict = PebbleDictionary()
    for ((k, value) in this) {
        when (value) {
            is String -> dict.addString(k, value)
            is UByteArray -> dict.addBytes(k, value.toByteArray())
            is ByteArray -> dict.addBytes(k, value)
            is Int -> dict.addInt32(k, value)
            is Long -> dict.addInt32(k, value.toInt())
            is ULong -> dict.addUint32(k, value.toInt())
            is UInt -> dict.addUint32(k, value.toInt())
            is Short -> dict.addInt16(k, value)
            is UShort -> dict.addUint16(k, value.toShort())
            is Byte -> dict.addInt8(k, value)
            is UByte -> dict.addUint8(k, value.toByte())
            else -> throw IllegalArgumentException("Unsupported type: ${value::class.simpleName}")
        }
    }

    return dict
}

/**
 * Intent broadcast to pebble.apk to indicate that a message was received from the watch. To avoid protocol timeouts
 * on the watch, applications *must* ACK or NACK all received messages.
 */
private const val INTENT_APP_ACK = "com.getpebble.action.app.ACK"

/**
 * Intent broadcast to pebble.apk to indicate that a message was unsuccessfully received from the watch.
 */
private const val INTENT_APP_NACK = "com.getpebble.action.app.NACK"

/**
 * Intent broadcast from pebble.apk containing one-or-more key-value pairs sent from the watch to the phone.
 */
private const val INTENT_APP_RECEIVE = "com.getpebble.action.app.RECEIVE"

/**
 * Intent broadcast from pebble.apk indicating that a sent message was successfully received by a watch app.
 */
private const val INTENT_APP_RECEIVE_ACK = "com.getpebble.action.app.RECEIVE_ACK"

/**
 * Intent broadcast from pebble.apk indicating that a sent message was not received by a watch app.
 */
private const val INTENT_APP_RECEIVE_NACK = "com.getpebble.action.app.RECEIVE_NACK"

/**
 * Intent broadcast to pebble.apk containing one-or-more key-value pairs to be sent to the watch from the phone.
 */
private const val INTENT_APP_SEND = "com.getpebble.action.app.SEND"

/**
 * The bundle-key used to store a message's transaction id.
 */
private const val TRANSACTION_ID = "transaction_id"

/**
 * The bundle-key used to store a message's UUID.
 */
private const val APP_UUID = "uuid"

/**
 * The bundle-key used to store a message's JSON payload send-to or received-from the watch.
 */
private const val MSG_DATA = "msg_data"
