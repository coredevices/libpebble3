package io.rebble.libpebblecommon.js

import android.net.Uri
import android.webkit.JavascriptInterface
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import androidx.core.net.toUri

class WebViewPrivatePKJSInterface(
    jsRunner: WebViewJsRunner,
    device: CompanionAppDevice,
    scope: CoroutineScope,
    outgoingAppMessages: MutableSharedFlow<AppMessageRequest>,
    logMessages: MutableSharedFlow<String>
): PrivatePKJSInterface(jsRunner, device, scope, outgoingAppMessages, logMessages) {

    companion object {
        private val logger = Logger.withTag(WebViewPrivatePKJSInterface::class.simpleName!!)
    }

    @JavascriptInterface
    fun startupScriptHasLoaded(data: String?) {
        logger.v { "Startup script has loaded: $data" }
        if (data == null) {
            logger.e { "Startup script has loaded, but data is null" }
            return
        }
        val uri = data.toUri()
        val params = uri.getQueryParameter("params")
        val paramsDecoded = Uri.decode(params)
        val paramsJson = Json.decodeFromString<Map<String, String>>(paramsDecoded)
        val jsUrl = paramsJson["loadUrl"] ?: run {
            logger.e { "No loadUrl in params" }
            return
        }
        scope.launch {
            jsRunner.loadAppJs(jsUrl)
        }
    }

    @JavascriptInterface
    override fun getTimelineTokenAsync(): String {
        return super.getTimelineTokenAsync()
    }

    @JavascriptInterface
    override fun logInterceptedSend() {
        super.logInterceptedSend()
    }

    @JavascriptInterface
    override fun privateFnConfirmReadySignal(success: Boolean) {
        super.privateFnConfirmReadySignal(success)
    }

    @JavascriptInterface
    override fun sendAppMessageString(jsonAppMessage: String): Int {
        return super.sendAppMessageString(jsonAppMessage)
    }

    @JavascriptInterface
    override fun privateLog(message: String) {
        super.privateLog(message)
    }

    @JavascriptInterface
    override fun onConsoleLog(level: String, message: String, source: String?) {
        super.onConsoleLog(level, message, source)
    }

    @JavascriptInterface
    override fun onError(message: String?, source: String?, line: Double?, column: Double?) {
        super.onError(message, source, line, column)
    }

    @JavascriptInterface
    override fun onUnhandledRejection(reason: String) {
        super.onUnhandledRejection(reason)
    }

    @JavascriptInterface
    override fun getVersionCode(): Int {
        return super.getVersionCode()
    }

    @JavascriptInterface
    override fun signalAppScriptLoadedByBootstrap() {
        super.signalAppScriptLoadedByBootstrap()
    }

    @JavascriptInterface
    override fun getActivePebbleWatchInfo(): String {
        return super.getActivePebbleWatchInfo()
    }
}
