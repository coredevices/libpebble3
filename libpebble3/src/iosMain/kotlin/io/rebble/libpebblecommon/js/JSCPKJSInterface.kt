package io.rebble.libpebblecommon.js

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.LibPebble

class JSCPKJSInterface(jsRunner: JsRunner, device: CompanionAppDevice, libPebble: LibPebble, jsTokenUtil: JsTokenUtil) :
    PKJSInterface(jsRunner, device, libPebble, jsTokenUtil), RegisterableJsInterface {
    private val logger = Logger.withTag("JSCPKJSInterface")
    override val interf = mapOf(
        "showSimpleNotificationOnPebble" to this::showSimpleNotificationOnPebble,
        "getAccountToken" to this::getAccountToken,
        "getWatchToken" to this::getWatchToken,
        "showToast" to this::showToast,
        "openURL" to this::openURL
    )
    override val name = "Pebble"

    override fun showToast(toast: String) {
        //TODO: Implement showToast for JSCPKJSInterface
        logger.e { "showToast() not implemented" }
    }

    override fun close() {
        // No-op
    }
}