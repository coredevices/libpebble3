package io.rebble.libpebblecommon.pebblekit.two

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.ErrorTracker
import io.rebble.libpebblecommon.connection.CompanionApp
import io.rebble.libpebblecommon.connection.UserFacingError
import io.rebble.libpebblecommon.di.LibPebbleKoinComponent
import io.rebble.libpebblecommon.js.CompanionAppDevice
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import io.rebble.libpebblecommon.services.appmessage.AppMessageData
import io.rebble.libpebblecommon.services.appmessage.AppMessageDictionary
import io.rebble.libpebblecommon.services.appmessage.AppMessageResult
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.PebbleDictionaryItem
import io.rebble.pebblekit2.common.model.ReceiveResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import io.rebble.pebblekit2.server.DefaultPebbleListenerConnector
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid
import kotlin.uuid.toJavaUuid

class PebbleKit2(
    private val device: CompanionAppDevice,
    private val appInfo: PbwAppInfo
) : LibPebbleKoinComponent, CompanionApp {
    private val nextTransactionId = atomic(0)
    private val targetPackages = appInfo.companionApp?.android?.apps.orEmpty().mapNotNull { it.pkg }
    private val connector = DefaultPebbleListenerConnector(getKoin().get(), targetPackages)
    private val errorTracker: ErrorTracker = getKoin().get<ErrorTracker>()

    val uuid: Uuid by lazy { Uuid.Companion.parse(appInfo.uuid) }
    private var runningScope: CoroutineScope? = null

    override suspend fun start(connectionScope: CoroutineScope) {
        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            logger.e(throwable) { "Unhandled exception in PebbleKit2 $uuid: ${throwable.message}" }
        }
        val scope = connectionScope + Job() + CoroutineName("PebbleKit2-$uuid") + exceptionHandler
        runningScope = scope

        launchIncomingAppMessageHandler(scope)

        scope.launch {
            val connectSuccess = connector.sendOnAppOpened(
                uuid.toJavaUuid(),
                WatchIdentifier(device.watchInfo.serial)
            )
            if (!connectSuccess) {
                val appName = appInfo.shortName
                val downloadUrl = appInfo.companionApp?.android?.url
                errorTracker.reportError(
                    UserFacingError.MissingCompanionApp(
                        "$appName needs a companion app to function properly",
                        appName,
                        uuid,
                        downloadUrl
                    )
                )
            }
        }
    }

    override suspend fun stop() {
        runningScope?.launch {
            connector.sendOnAppClosed(uuid.toJavaUuid(), WatchIdentifier(device.watchInfo.serial))
        }

        runningScope?.launch {
            withContext(NonCancellable) {
                // Close the service within a couple of seconds to allow the service to perform clean up operations
                delay(5.seconds)
                connector.close()
            }

            runningScope?.cancel()
        }
    }


    fun isAllowedToCommunicate(pkg: String): Boolean {
        return targetPackages.contains(pkg)
    }

    suspend fun sendMessage(pebbleDictionary: PebbleDictionary): AppMessageResult {
        val transactionId = (nextTransactionId.getAndIncrement() % UByte.MAX_VALUE.toInt()).toUByte()
        return device.sendAppMessage(AppMessageData(transactionId, uuid, pebbleDictionary.toAppMessageDict()))
    }

    private fun launchIncomingAppMessageHandler(scope: CoroutineScope) {
        device.inboundAppMessages(uuid).onEach { appMessageData ->
            try {
                logger.d { "Got inbound AppMessage" }

                val pebbleDictionary = appMessageData.data.toPebbleDictionary()
                val result = connector.sendOnMessageReceived(
                    uuid.toJavaUuid(),
                    pebbleDictionary,
                    WatchIdentifier(device.watchInfo.serial)
                )

                if (result == ReceiveResult.Ack) {
                    replyACK(appMessageData.transactionId)
                } else {
                    replyNACK(appMessageData.transactionId)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.e(e) { "Error receiving app message from $uuid: ${e.message}" }
            }
        }.launchIn(scope)
    }

    private suspend fun replyNACK(id: UByte) {
        withTimeoutOrNull(3.seconds) {
            device.sendAppMessageResult(AppMessageResult.ACK(id))
        }
    }

    private suspend fun replyACK(id: UByte) {
        withTimeoutOrNull(1000) {
            device.sendAppMessageResult(AppMessageResult.ACK(id))
        }
    }


    companion object {
        private val logger = Logger.Companion.withTag(PebbleKit2::class.simpleName!!)
    }
}

private fun PebbleDictionary.toAppMessageDict(): AppMessageDictionary {
    return map { it.key.toInt() to it.value.value }.toMap()
}

private fun AppMessageDictionary.toPebbleDictionary(): PebbleDictionary {
    return map {
        val key = it.key.toUInt()
        val value = when (val rawValue = it.value) {
            is String -> PebbleDictionaryItem.String(rawValue)
            is UByteArray -> PebbleDictionaryItem.ByteArray(rawValue.toByteArray())
            is ByteArray -> PebbleDictionaryItem.ByteArray(rawValue)
            is Int -> PebbleDictionaryItem.Int32(rawValue)
            is Long -> PebbleDictionaryItem.Int32(rawValue.toInt())
            is ULong -> PebbleDictionaryItem.UInt32(rawValue.toUInt())
            is UInt -> PebbleDictionaryItem.UInt32(rawValue)
            is Short -> PebbleDictionaryItem.Int16(rawValue)
            is UShort -> PebbleDictionaryItem.UInt16(rawValue)
            is Byte -> PebbleDictionaryItem.Int8(rawValue)
            is UByte -> PebbleDictionaryItem.UInt8(rawValue)
            else -> throw IllegalArgumentException("Unsupported type: ${rawValue::class.simpleName}")
        }

        key to value
    }.toMap()

}
