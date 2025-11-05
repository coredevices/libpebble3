package io.rebble.libpebblecommon.js

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.js.JSCGeolocationInterface
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.js.JSCJSLocalStorageInterface
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.plus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.Json
import platform.Foundation.NSBundle
import platform.Foundation.NSSelectorFromString
import platform.Foundation.NSURL
import platform.JavaScriptCore.JSContext
import platform.JavaScriptCore.JSValue
import kotlin.native.runtime.GC
import kotlin.native.runtime.NativeRuntimeApi

class JavascriptCoreJsRunner(
    private val appContext: AppContext,
    private val libPebble: LibPebble,
    private val jsTokenUtil: JsTokenUtil,

    device: CompanionAppDevice,
    private val scope: CoroutineScope,
    appInfo: PbwAppInfo,
    lockerEntry: LockerEntry,
    jsPath: Path,
    urlOpenRequests: Channel<String>,
    private val logMessages: MutableSharedFlow<String>,
    private val pkjsBundleIdentifier: String? = "coredevices.coreapp",
): JsRunner(appInfo, lockerEntry, jsPath, device, urlOpenRequests) {
    private var jsContext: JSContext? = null
    private val logger = Logger.withTag("JSCRunner-${appInfo.longName}")
    private var interfaces: List<RegisterableJsInterface>? = null
    @OptIn(DelicateCoroutinesApi::class)
    private val threadContext = newSingleThreadContext("JSRunner-${appInfo.uuid}")

    private fun initInterfaces(jsContext: JSContext) {
        fun eval(js: String) = this.jsContext?.evalCatching(js)
        fun evalRaw(js: String): JSValue? = this.jsContext?.evaluateScript(js)
        val interfacesScope = scope + threadContext
        val instances = listOf(
            XMLHTTPRequestManager(interfacesScope, ::eval),
            JSTimeout(interfacesScope, ::evalRaw),
            JSCPKJSInterface(this, device, libPebble, jsTokenUtil),
            JSCPrivatePKJSInterface(jsPath, this, device, interfacesScope, _outgoingAppMessages, logMessages),
            JSCJSLocalStorageInterface(jsContext, appInfo.uuid, appContext, ::evalRaw),
            JSCGeolocationInterface(interfacesScope, this)
        )
        instances.forEach {
            jsContext[it.name] = it.interf
            it.onRegister(jsContext)
        }
        interfaces = instances
    }

    private fun evaluateInternalScript(filenameNoExt: String) {
        val bundle = pkjsBundleIdentifier
            ?.let { NSBundle.bundleWithIdentifier(it) ?: error("PKJS bundle with identifier $it not found") }
            ?: NSBundle.mainBundle
        val path = bundle.pathForResource(filenameNoExt, "js")
            ?: error("Startup script not found in bundle")
        val js = SystemFileSystem.source(Path(path)).buffered().use {
            it.readString()
        }
        runBlocking(threadContext) {
            jsContext?.evalCatching(js, NSURL.fileURLWithPath(path))
        }
    }

    private fun exceptionHandler(context: JSContext?, exception: JSValue?) {
        val decoded: Any? = when {
            exception == null -> null
            exception.isObject() -> exception.toDictionary()?.let { JSError.fromDictionary(it) }
            else -> exception.toString()
        }
        logger.d { "JS Exception: ${exception?.toObject()}" }
        logger.e { "JS Exception: $decoded" }
    }

    private fun setupJsContext() {
        runBlocking(threadContext) {
            val jsContext = JSContext()
            this@JavascriptCoreJsRunner.jsContext = jsContext
            initInterfaces(jsContext)
            jsContext.exceptionHandler = ::exceptionHandler
            jsContext.setName("PKJS: ${appInfo.longName}")
            val selector = NSSelectorFromString("setInspectable:")
            if (jsContext.respondsToSelector(selector)) {
                jsContext.setInspectable(true)
            } else {
                logger.w { "JSContext.setInspectable not available on this iOS version" }
            }
        }
    }

    @OptIn(NativeRuntimeApi::class)
    private fun tearDownJsContext() {
        scope.cancel()
        _readyState.value = false
        runBlocking(threadContext) {
            interfaces?.forEach { it.close() }
            interfaces = null
            jsContext?.finalize()
            jsContext = null
        }
        GC.collect()
        threadContext.close()
    }

    private fun evaluateStandardLib() {
        evaluateInternalScript("XMLHTTPRequest")
        evaluateInternalScript("JSTimeout")
    }

    private val navigator = mapOf(
        "userAgent" to "PKJS",
        "geolocation" to emptyMap<String, Any>()
    )

    private fun setupNavigator() {
        jsContext?.set("navigator", navigator)
    }

    override suspend fun start() {
        setupJsContext()
        setupNavigator()
        logger.d { "JS Context set up" }
        evaluateStandardLib()
        logger.d { "Standard lib scripts evaluated" }
        evaluateInternalScript("startup")
        logger.d { "Startup script evaluated" }
        loadAppJs(jsPath.toString())
    }

    override suspend fun stop() {
        logger.d { "Stopping JS Context" }
        tearDownJsContext()
        logger.d { "JS Context torn down" }
    }

    override suspend fun loadAppJs(jsUrl: String) {
        SystemFileSystem.source(Path(jsUrl)).buffered().use {
            val js = it.readString()
            withContext(threadContext) {
                jsContext?.evalCatching(js, NSURL.fileURLWithPath(jsUrl))
            }
        }
        signalReady()
    }

    override suspend fun signalNewAppMessageData(data: String?): Boolean {
        withContext(threadContext) {
            jsContext?.evalCatching("globalThis.signalNewAppMessageData(${Json.encodeToString(data)})")
        }
        return true
    }

    override suspend fun signalAppMessageAck(data: String?): Boolean {
        withContext(threadContext) {
            jsContext?.evalCatching("globalThis.signalAppMessageAck(${Json.encodeToString(data)})")
        }
        return jsContext != null
    }

    override suspend fun signalAppMessageNack(data: String?): Boolean {
        withContext(threadContext) {
            jsContext?.evalCatching("globalThis.signalAppMessageNack(${Json.encodeToString(data)})")
        }
        return jsContext != null
    }

    override suspend fun signalTimelineToken(callId: String, token: String) {
        val tokenJson = Json.encodeToString(mapOf("userToken" to token, "callId" to callId))
        withContext(threadContext) {
            jsContext?.evalCatching("globalThis.signalTimelineTokenSuccess($tokenJson)")
        }
    }

    override suspend fun signalTimelineTokenFail(callId: String) {
        val tokenJson = Json.encodeToString(mapOf("userToken" to null, "callId" to callId))
        withContext(threadContext) {
            jsContext?.evalCatching("globalThis.signalTimelineTokenFailure($tokenJson)")
        }
    }

    override suspend fun signalReady() {
        withContext(threadContext) {
            jsContext?.evalCatching("globalThis.signalReady()")
        }
    }

    override suspend fun signalShowConfiguration() {
        withContext(threadContext) {
            jsContext?.evalCatching("globalThis.signalShowConfiguration()")
        }
    }

    override suspend fun signalWebviewClosed(data: String?) {
        withContext(threadContext) {
            jsContext?.evalCatching("globalThis.signalWebviewClosedEvent(${Json.encodeToString(data)})")
        }
    }

    override suspend fun eval(js: String) {
        withContext(threadContext) {
            jsContext?.evalCatching(js)
        }
    }

    override suspend fun evalWithResult(js: String): Any? {
        return withContext(threadContext) {
            jsContext?.evalCatching(js)?.toObject()
        }
    }
}