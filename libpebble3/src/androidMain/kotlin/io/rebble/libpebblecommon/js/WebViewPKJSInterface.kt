package io.rebble.libpebblecommon.js

import android.content.Context
import android.webkit.JavascriptInterface
import android.widget.Toast
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.LibPebble

class WebViewPKJSInterface(
    jsRunner: JsRunner,
    device: CompanionAppDevice,
    private val context: Context,
    libPebble: LibPebble,
    jsTokenUtil: JsTokenUtil,
): PKJSInterface(jsRunner, device, libPebble, jsTokenUtil) {
    companion object {
        private val logger = Logger.withTag(WebViewPKJSInterface::class.simpleName!!)
    }
    @JavascriptInterface
    override fun showSimpleNotificationOnPebble(title: String, notificationText: String) {
        super.showSimpleNotificationOnPebble(title, notificationText)
    }

    @JavascriptInterface
    override fun getAccountToken(): String {
        return super.getAccountToken()
    }

    @JavascriptInterface
    override fun getWatchToken(): String {
        return super.getWatchToken()
    }

    @JavascriptInterface
    override fun showToast(toast: String) {
        Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
    }

    @JavascriptInterface
    override fun openURL(url: String): String {
        return super.openURL(url)
    }
}
