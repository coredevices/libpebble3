package io.rebble.libpebblecommon.js

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.request.basicAuth
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.util.encodeBase64
import io.ktor.util.flattenEntries
import io.ktor.utils.io.charsets.MalformedInputException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.Foundation.NSNull
import platform.JavaScriptCore.JSValue

private const val UNSENT = 0
private const val OPENED = 1
private const val HEADERS = 2
private const val LOADING = 3
private const val DONE = 4

class XMLHTTPRequestManager(private val scope: CoroutineScope, private val eval: (String) -> JSValue?): RegisterableJsInterface {
    private var lastInstance = 0
    private val instances = mutableMapOf<Int, XHRInstance>()
    private val client = HttpClient(Darwin)
    private val logger = Logger.withTag("XMLHTTPRequestManager")
    override val interf = mapOf(
        "getXHRInstanceID" to this::getXHRInstanceID,
        "open" to this::open,
        "setRequestHeader" to this::setRequestHeader,
        "send" to this::send,
        "abort" to this::abort,
    )

    override val name = "_XMLHTTPRequestManager"

    private fun getXHRInstanceID(): Int {
        val id = ++lastInstance
        instances[id] = XHRInstance(id)
        return id
    }

    // JSC uses double for numbers
    private fun open(instanceId: Double, method: String, url: String, async: Boolean, user: String, password: String) {
        logger.d { "Instance $instanceId open()" }
        val userNullable = user.ifEmpty { null }
        val passwordNullable = password.ifEmpty { null }
        instances[instanceId.toInt()]?.open(method, url, async, userNullable, passwordNullable)
    }

    private fun setRequestHeader(instanceId: Double, header: String, value: Any) {
        instances[instanceId.toInt()]?.setRequestHeader(header, value)
    }

    private fun send(instanceId: Double, responseType: String, data: Any?) {
        logger.d { "Instance $instanceId send()" }
        val responseTypeNullable = responseType.ifEmpty { null }
        val bytes = when (data) {
            is ByteArray -> data
            is String -> data.encodeToByteArray()
            is NSNull, null -> null
            else -> {
                logger.e { "Invalid data type for send: ${data::class.simpleName}" }
                null
            }
        }
        instances[instanceId.toInt()]?.send(bytes, responseTypeNullable)
    }

    private fun abort(instanceId: Double) {
        instances[instanceId.toInt()]?.abort()
    }

    inner class XHRInstance(val id: Int) {
        private var async: Boolean = true
        private var url: String? = null
        private var method: HttpMethod? = null
        private var user: String? = null
        private var password: String? = null
        private val headers = mutableMapOf<String, Any>()
        var requestJob: Job? = null

        private val jsInstance = "XMLHttpRequest._instances.get($id)"

        private fun changeReadyState(newState: Int) {
            eval("$jsInstance.readyState = $newState")
            dispatchEvent(XHREvent.ReadyStateChange)
        }

        private fun dispatchEvent(event: XHREvent) {
            val evt = "{\"type\": \"${event.toJsName()}\"}"
            eval("$jsInstance._dispatchEvent(${Json.encodeToString(event.toJsName())}, ${Json.encodeToString(evt)})")
        }

        fun open(method: String, url: String, async: Boolean?, user: String?, password: String?) {
            this.async = async ?: true
            this.url = url
            this.method = HttpMethod.parse(method)
            this.user = user
            this.password = password
            this.headers.clear()
            if (!this.async) {
                logger.w { "Synchronous XHR opened" }
            }
            changeReadyState(OPENED)
        }

        fun setRequestHeader(header: String, value: Any) {
            headers[header] = value
        }

        private fun dispatchError() {
            changeReadyState(DONE)
            dispatchEvent(XHREvent.Error)
        }

        fun send(data: ByteArray?, responseType: String?) {
            suspend fun execute() {
                if (async) {
                    dispatchEvent(XHREvent.LoadStart)
                }
                val response = try {
                    client.request {
                        this.method = this@XHRInstance.method!!
                        this.url(this@XHRInstance.url!!)
                        if (user != null && password != null) {
                            basicAuth(user!!, password!!)
                        }
                        if (data != null) {
                            setBody(data)
                        }
                        this@XHRInstance.headers.entries.forEach {
                            header(it.key, it.value)
                        }
                    }
                } catch (e: TimeoutCancellationException) {
                    logger.e { "Request timed out: ${e.message}" }
                    scope.launch {
                        changeReadyState(DONE)
                        dispatchEvent(XHREvent.Timeout)
                    }
                    return
                } catch (e: Exception) {
                    logger.e(e) { "Request (size ${data?.size}) failed: ${e.message}" }
                    scope.launch { dispatchError() }
                    return
                }
                val responseHeaders = try {
                    Json.encodeToString(response.headers
                        .flattenEntries()
                        .toMap()
                        .mapKeys { it.key.lowercase() })
                } catch (e: Throwable) {
                    logger.e(e) { "Failed to serialize response headers: ${e.message}" }
                    scope.launch { dispatchError() }
                    return
                }
                val body = try {
                    when (responseType) {
                        "arraybuffer" -> Json.encodeToString(response.bodyAsBytes().encodeBase64())
                        "text", "", "json", null -> {
                            val text = try {
                                response.bodyAsText()
                            } catch (e: MalformedInputException) {
                                logger.e(e) { "Response has charset errors: ${e.message}. Decoding as UTF-8 with replacements" }
                                // Replace invalid sequences
                                response.bodyAsBytes().decodeToString(throwOnInvalidSequence = false)
                            }
                            Json.encodeToString(text)
                        }
                        else -> {
                            logger.e { "Invalid response type: $responseType" }
                            "null"
                        }
                    }
                } catch (e: Throwable) {
                    logger.e(e) { "Failed to read response body (type $responseType): ${e.message}" }
                    scope.launch { dispatchError() }
                    return
                }
                val status = Json.encodeToString(response.status.value)
                val statusText = Json.encodeToString(response.status.description)
                logger.v { "XHR Response: $status $statusText" }
                scope.launch {
                    eval("$jsInstance._onResponseComplete($responseHeaders, $status, $statusText, $body)")
                    changeReadyState(DONE)
                    dispatchEvent(XHREvent.Load)
                    dispatchEvent(XHREvent.LoadEnd)
                }
            }
            if (async) {
                requestJob = scope.launch(Dispatchers.IO) {
                    execute()
                }
            } else {
                runBlocking(Dispatchers.IO) {
                    execute()
                }
            }
        }

        fun abort() {
            requestJob?.cancel("Aborted by JS")
            requestJob = null
            changeReadyState(DONE)
            dispatchEvent(XHREvent.Abort)
        }
    }

    override fun close() {
        client.close()
        instances.values.forEach { it.requestJob?.cancel("Closing") }
        instances.clear()
    }
}

enum class XHREvent {
    Abort,
    Error,
    Load,
    LoadEnd,
    LoadStart,
    Progress,
    ReadyStateChange,
    Timeout;
    companion object {
        fun fromString(event: String): XHREvent? {
            return entries.firstOrNull { it.name.equals(event, ignoreCase = true) }
        }
    }

    fun toJsName(): String {
        return name.lowercase()
    }
}

@Serializable
data class CompleteXHRResponse(
    val responseHeaders: Map<String, String>,
    val responseData: String,
    val status: Int,
    val contentType: String?
)