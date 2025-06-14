package io.rebble.libpebblecommon.js

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.js.JsRunner
import io.rebble.libpebblecommon.js.PebbleJSDevice
import io.rebble.libpebblecommon.js.PrivatePKJSInterface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.io.files.Path
import platform.JavaScriptCore.JSContext

class JSCPrivatePKJSInterface(
    private val jsPath: Path,
    jsRunner: JsRunner,
    device: PebbleJSDevice,
    scope: CoroutineScope,
    outgoingAppMessages: MutableSharedFlow<Pair<CompletableDeferred<Byte>, String>>
): PrivatePKJSInterface(jsRunner, device, scope, outgoingAppMessages), RegisterableJsInterface {
    private val logger = Logger.withTag("JSCPrivatePKJSInterface")

    override fun register(jsContext: JSContext) {
        jsContext["_Pebble"] = mapOf(
            "sendAppMessageString" to this::sendAppMessageString,
            "privateLog" to this::privateLog,
            "logInterceptedSend" to this::logInterceptedSend,
            "getVersionCode" to this::getVersionCode,
            "getTimelineTokenAsync" to this::getTimelineTokenAsync,
            "privateFnConfirmReadySignal" to this::privateFnConfirmReadySignal,
        )
    }

    override fun close() {
        // No-op
    }
}