package io.rebble.libpebblecommon.io.rebble.libpebblecommon.js

import io.rebble.libpebblecommon.js.GeolocationInterface
import io.rebble.libpebblecommon.js.JsRunner
import io.rebble.libpebblecommon.js.RegisterableJsInterface
import io.rebble.libpebblecommon.js.set
import kotlinx.coroutines.CoroutineScope
import platform.JavaScriptCore.JSContext

class JSCGeolocationInterface(
    scope: CoroutineScope,
    jsRunner: JsRunner
): GeolocationInterface(scope, jsRunner), RegisterableJsInterface {
    override val interf = mapOf(
        "getCurrentPosition" to this::getCurrentPosition,
        "watchPosition" to this::watchPosition,
        "clearWatch" to this::clearWatch,
        "getRequestCallbackID" to this::getRequestCallbackID,
        "getWatchCallbackID" to this::getWatchCallbackID
    )
    override val name = "_PebbleGeo"

    override fun close() {

    }
}