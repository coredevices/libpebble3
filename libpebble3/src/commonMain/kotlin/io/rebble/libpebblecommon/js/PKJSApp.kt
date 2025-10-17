package io.rebble.libpebblecommon.js

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.CompanionApp
import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.di.LibPebbleKoinComponent
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import io.rebble.libpebblecommon.services.appmessage.AppMessageData
import io.rebble.libpebblecommon.services.appmessage.AppMessageDictionary
import io.rebble.libpebblecommon.services.appmessage.AppMessageResult
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.koin.core.component.get
import org.koin.core.parameter.parameterArrayOf
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class PKJSApp(
    val device: CompanionAppDevice,
    private val jsPath: Path,
    val appInfo: PbwAppInfo,
    val lockerEntry: LockerEntry,
): LibPebbleKoinComponent, CompanionApp {
    companion object {
        private val logger = Logger.withTag(PKJSApp::class.simpleName!!)
    }
    val uuid: Uuid by lazy { Uuid.parse(appInfo.uuid) }
    private var jsRunner: JsRunner? = null
    private var runningScope: CoroutineScope? = null
    private val urlOpenRequests = Channel<String>(Channel.RENDEZVOUS)

    private val _logMessages = MutableSharedFlow<String>(10)
    val logMessages = _logMessages.asSharedFlow()
    val sessionIsReady get() = jsRunner?.readyState?.value ?: false

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
            jsRunner?.let { runner ->
                if (!runner.readyState.value) {
                    logger.w { "JsRunner not ready, waiting" }
                    val result = withTimeoutOrNull(6.seconds) {
                        runner.readyState.first { it }
                    } ?: false
                    if (!result) {
                        logger.w { "JsRunner still not ready after waiting, sending NACK" }
                        replyNACK(appMessageData.transactionId)
                        return@onEach
                    }
                }
                replyACK(appMessageData.transactionId)
                val dataString = appMessageData.data.toJSData(appInfo.appKeys)
                logger.d("Received app message: ${appMessageData.transactionId}")
                runner.signalNewAppMessageData(dataString)
            } ?: run {
                logger.w { "JsRunner not init'd, sending NACK" }
                replyNACK(appMessageData.transactionId)
                return@onEach
            }
        }.catch {
            logger.e(it) { "Error receiving app message: ${it.message}" }
        }.launchIn(scope)
    }

    private fun launchOutgoingAppMessageHandler(device: ConnectedPebble.AppMessages, scope: CoroutineScope) {
        jsRunner?.outgoingAppMessages?.onEach { request ->
            logger.d { "Sending app message: ${request.data}" }
            val tID = device.transactionSequence.next()
            val appMessage = try {
                request.data.toAppMessageData(appInfo, tID)
            } catch (e: IllegalArgumentException) {
                logger.e(e) { "Invalid app message data" }
                request.state.value = AppMessageRequest.State.DataError
                return@onEach
            }
            request.state.value = AppMessageRequest.State.TransactionId(tID)
            val result = device.sendAppMessage(appMessage)
            request.state.value = AppMessageRequest.State.Sent(result)
        }?.catch {
            logger.e(it) { "Error sending app message" }
        }?.launchIn(scope) ?: error("JsRunner not initialized")
    }

    suspend fun requestConfigurationUrl(): String? {
        if (runningScope == null) {
            logger.e { "Cannot show configuration, PKJSApp is not running" }
            return null
        }
        val url = runningScope!!.async { urlOpenRequests.receive() }
        jsRunner?.signalShowConfiguration() ?: logger.e { "JsRunner not initialized, cannot show configuration" }
        return url.await()
    }

    private fun injectJsRunner(scope: CoroutineScope): JsRunner =
        get {
            parameterArrayOf(
                device,
                scope,
                appInfo,
                lockerEntry,
                jsPath,
                urlOpenRequests,
                _logMessages
            )
        }

    override suspend fun start(connectionScope: CoroutineScope) {
        val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
            logger.e(throwable) { "Unhandled exception in PKJSApp: ${throwable.message}" }
        }
        val scope = connectionScope + Job() + CoroutineName("PKJSApp-$uuid") + exceptionHandler
        runningScope = scope
        jsRunner = injectJsRunner(scope)
        launchIncomingAppMessageHandler(device, scope)
        launchOutgoingAppMessageHandler(device, scope)
        jsRunner?.start() ?: error("JsRunner not initialized")
    }

    override suspend fun stop() {
        jsRunner?.stop()
        runningScope?.cancel()
        jsRunner = null
    }

    fun triggerOnWebviewClosed(data: String) {
        runningScope?.launch {
            jsRunner?.signalWebviewClosed(data)
        }
    }
}

fun AppMessageDictionary.toJSData(appKeys: Map<String, Int>): String {
    val data = this.mapKeys {
        val id = it.key
        appKeys.entries.firstOrNull { it.value == id }?.key ?: id
    }
    return buildJsonObject {
        for ((key, value) in data) {
            when (value) {
                is String -> put(key.toString(), value)
                is Number -> put(key.toString(), value)
                is UByteArray -> {
                    val array = buildJsonArray {
                        for (byte in value) {
                            add(byte.toInt())
                        }
                    }
                    put(key.toString(), array)
                }
                is ByteArray -> {
                    val array = buildJsonArray {
                        for (byte in value) {
                            add(byte.toInt())
                        }
                    }
                    put(key.toString(), array)
                }
                is Boolean -> put(key.toString(), value)
                else -> error("Invalid JSON value, unsupported type ${value::class.simpleName}")
            }
        }
    }.toString()
}

private fun String.toAppMessageData(appInfo: PbwAppInfo, transactionId: UByte): AppMessageData {
    val jsonElement = Json.parseToJsonElement(this)
    require(jsonElement !is JsonNull) { "Invalid JSON data" }
    val jsonObject = jsonElement.jsonObject
    val tuples = jsonObject.mapNotNull { objectEntry ->
        val key = objectEntry.key
        val keyId = appInfo.appKeys[key] ?: key.toIntOrNull() ?: return@mapNotNull null
        when (objectEntry.value) {
            is JsonArray -> {
                Pair(keyId, objectEntry.value.jsonArray.map { it.jsonPrimitive.long.toUByte() }.toUByteArray())
            }
            is JsonObject -> error("Invalid JSON value, JsonObject not supported")
            else -> {
                when {
                    objectEntry.value.jsonPrimitive.isString -> {
                        Pair(keyId, objectEntry.value.jsonPrimitive.content)
                    }
                    objectEntry.value.jsonPrimitive.intOrNull != null -> {
                        Pair(keyId, objectEntry.value.jsonPrimitive.long.toInt())
                    }
                    objectEntry.value.jsonPrimitive.longOrNull != null -> {
                        Pair(keyId, objectEntry.value.jsonPrimitive.long.toUInt())
                    }
                    objectEntry.value.jsonPrimitive.booleanOrNull != null -> {
                        Pair(keyId, objectEntry.value.jsonPrimitive.boolean)
                    }
                    objectEntry.value.jsonPrimitive.doubleOrNull != null -> {
                        val value = objectEntry.value.jsonPrimitive.double.toInt()
                        Pair(keyId, value)
                    }
                    objectEntry.value.jsonPrimitive.floatOrNull != null -> {
                        val value = objectEntry.value.jsonPrimitive.float.toInt()
                        Pair(keyId, value)
                    }
                    else -> {
                        Logger.e("toAppMessageData") { "Invalid JSON value for key $key: ${objectEntry.value}" }
                        null // Skip unsupported types
                    }
                }
            }
        }
    }.toMap()
    return AppMessageData(
        transactionId = transactionId,
        uuid = Uuid.parse(appInfo.uuid),
        data = tuples
    )
}
