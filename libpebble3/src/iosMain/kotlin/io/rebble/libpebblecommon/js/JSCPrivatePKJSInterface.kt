package io.rebble.libpebblecommon.js

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.io.files.Path
import platform.JavaScriptCore.JSContext

class JSCPrivatePKJSInterface(
    private val jsPath: Path,
    jsRunner: JsRunner,
    device: PebbleJSDevice,
    scope: CoroutineScope,
    outgoingAppMessages: MutableSharedFlow<AppMessageRequest>,
    logMessages: MutableSharedFlow<String>
): PrivatePKJSInterface(jsRunner, device, scope, outgoingAppMessages, logMessages), RegisterableJsInterface {
    private val logger = Logger.withTag("JSCPrivatePKJSInterface")

    override val interf = mapOf(
        "sendAppMessageString" to this::sendAppMessageString,
        "privateLog" to this::privateLog,
        "onConsoleLog" to { level: String, message: String, source: String? ->
            val sourceFmt = source?.let {
                "at ${it.substringAfter("code@")}"
            }
            this.onConsoleLog(level, message, sourceFmt)
        },
        "onError" to this::onError,
        "onUnhandledRejection" to this::onUnhandledRejection,
        "logInterceptedSend" to this::logInterceptedSend,
        "getVersionCode" to this::getVersionCode,
        "getTimelineTokenAsync" to this::getTimelineTokenAsync,
        "privateFnConfirmReadySignal" to this::privateFnConfirmReadySignal,
        "getActivePebbleWatchInfo" to this::getActivePebbleWatchInfo,
    )

    override val name: String = "_Pebble"

    override fun close() {
        // No-op
    }
}