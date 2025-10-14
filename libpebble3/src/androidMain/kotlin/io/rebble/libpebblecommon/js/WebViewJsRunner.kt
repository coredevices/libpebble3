package io.rebble.libpebblecommon.js

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Message
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.di.LibPebbleKoinComponent
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.js.WebViewGeolocationInterface
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.js.WebViewJSLocalStorageInterface
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import androidx.core.net.toUri
import java.io.File


class WebViewJsRunner(
    appContext: AppContext,
    libPebble: LibPebble,
    jsTokenUtil: JsTokenUtil,

    device: CompanionAppDevice,
    private val scope: CoroutineScope,
    appInfo: PbwAppInfo,
    lockerEntry: LockerEntry,
    jsPath: Path,
    urlOpenRequests: Channel<String>,
    private val logMessages: MutableSharedFlow<String>,

    ): JsRunner(appInfo, lockerEntry, jsPath, device, urlOpenRequests), LibPebbleKoinComponent {
    private val context = appContext.context
    companion object {
        const val API_NAMESPACE = "Pebble"
        const val PRIVATE_API_NAMESPACE = "_$API_NAMESPACE"
        const val STARTUP_URL = "file:///android_asset/webview_startup.html"
        private val logger = Logger.withTag(WebViewJsRunner::class.simpleName!!)
    }

    private var webView: WebView? = null
    private val initializedLock = Object()
    private val publicJsInterface = WebViewPKJSInterface(this, device, context, libPebble, jsTokenUtil)
    private val privateJsInterface = WebViewPrivatePKJSInterface(this, device, scope, _outgoingAppMessages, logMessages)
    private val localStorageInterface = WebViewJSLocalStorageInterface(appInfo.uuid, appContext) {
        runBlocking(Dispatchers.Main) {
            webView?.evaluateJavascript(
                it,
                null
            )
        }
    }
    private val geolocationInterface = WebViewGeolocationInterface(scope, this)
    private val interfaces = setOf(
            Pair(API_NAMESPACE, publicJsInterface),
            Pair(PRIVATE_API_NAMESPACE, privateJsInterface),
            Pair("_localStorage", localStorageInterface),
            Pair("_PebbleGeo", geolocationInterface)
    )

    private val webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            return true
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            logger.d { "Page finished loading: $url" }
        }

        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
            super.onReceivedError(view, request, error)
            logger.e {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    "Error loading page: ${error?.errorCode} ${error?.description}"
                } else {
                    "Error loading page: ${error?.toString()}"
                }
            }
        }

        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            super.onReceivedSslError(view, handler, error)
            logger.e { "SSL error loading page: ${error?.primaryError}" }
            handler?.cancel()
        }

        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            if (isForbidden(request?.url)) {
                return object : WebResourceResponse("text/plain", "utf-8", null) {
                    override fun getStatusCode(): Int {
                        return 403
                    }

                    override fun getReasonPhrase(): String {
                        return "Forbidden"
                    }
                }
            } else {
                return super.shouldInterceptRequest(view, request)
            }
        }
    }

    private fun isForbidden(url: Uri?): Boolean {
        return if (url == null) {
            logger.w { "Blocking null URL" }
            true
        } else if (url.scheme?.uppercase() != "FILE") {
            false
        } else if (url.path?.uppercase() == jsPath.toString().uppercase()) {
            false
        } else {
            logger.w { "Blocking access to file: ${url.path}" }
            true
        }
    }

    private val chromeClient = object : WebChromeClient() {

        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message?): Boolean {
            return false
        }

        override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            return false
        }

        override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            return false
        }

        override fun onJsBeforeUnload(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
            return false
        }

        override fun onJsPrompt(view: WebView?, url: String?, message: String?, defaultValue: String?, result: JsPromptResult?): Boolean {
            return false
        }

        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            //Stub
        }

        override fun onShowFileChooser(webView: WebView?, filePathCallback: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?): Boolean {
            return false
        }

        override fun onPermissionRequest(request: PermissionRequest?) {
            logger.d { "Permission request for: ${request?.resources?.joinToString()}" }
            request?.deny()
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String?,
            callback: GeolocationPermissions.Callback?
        ) {
            callback?.invoke(origin, false, false)
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private suspend fun init() = withContext(Dispatchers.Main) {
        webView = WebView(context).also {
            it.setWillNotDraw(true)
            val settings = it.settings
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = false

            //TODO: use WebViewAssetLoader instead
            settings.allowUniversalAccessFromFileURLs = true
            settings.allowFileAccessFromFileURLs = true

            settings.setGeolocationEnabled(true)
            settings.databaseEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            it.clearCache(true)

            interfaces.forEach { (namespace, jsInterface) ->
                it.addJavascriptInterface(jsInterface, namespace)
            }
            it.webViewClient = webViewClient
            it.webChromeClient = chromeClient
        }
    }

    private fun shimLocalStorage() {
        runBlocking(Dispatchers.Main) {
            webView?.evaluateJavascript("""
                if (!localStorage.__override__) {
                        localStorage.clear();
                }
                localStorage.__override__ = true;
                localStorage = {};
                localStorage.length = 0;
                Object.setPrototypeOf(localStorage, {
                    clear() { _localStorage.clear(); },
                    getItem(key) { return JSON.parse(_localStorage.getItem(key)); },
                    setItem(key, value) { _localStorage.setItem(key, value); },
                    removeItem(key) { _localStorage.removeItem(key); },
                    key(index) { return _localStorage.key(index); }
                });
            """.trimIndent()
            ) {
                logger.d { "localStorage shimmed" }
            }
        }
    }


    override suspend fun start() {
        synchronized(initializedLock) {
            check(webView == null) { "WebviewJsRunner already started" }
        }
        try {
            init()
        } catch (e: Exception) {
            synchronized(initializedLock) {
                webView = null
            }
            throw e
        }
        check(webView != null) { "WebView not initialized" }
        logger.d { "WebView initialized" }
        loadApp(jsPath.toString())
    }

    override suspend fun stop() {
        //TODO: Close config screens
        _readyState.value = false
        withContext(Dispatchers.Main) {
            interfaces.forEach { (namespace, _) ->
                webView?.removeJavascriptInterface(namespace)
            }
            webView?.loadUrl("about:blank")
            webView?.stopLoading()
            webView?.clearHistory()
            webView?.removeAllViews()
            webView?.clearCache(true)
            webView?.destroy()
        }
        synchronized(initializedLock) {
            webView = null
        }
    }

    private suspend fun loadApp(url: String) {
        check(webView != null) { "WebView not initialized" }
        withContext(Dispatchers.Main) {
            webView?.loadUrl(
                STARTUP_URL.toUri().buildUpon()
                    .appendQueryParameter("params", "{\"loadUrl\": \"$url\"}")
                    .build()
                    .toString()
            )
        }
    }

    override suspend fun loadAppJs(jsUrl: String) {
        webView?.let { webView ->
            shimLocalStorage()

            if (jsUrl.isBlank() || !jsUrl.endsWith(".js")) {
                logger.e { "loadUrl passed to loadAppJs empty or invalid" }
                return
            }

            val urlAsUri = Uri.fromFile(File(jsUrl)).toString()

            withContext(Dispatchers.Main) {
                webView.evaluateJavascript(
                        """
                            (() => {
                                const signalLoaded = () => {
                                    _Pebble.signalAppScriptLoadedByBootstrap();
                                }
                                const head = document.getElementsByTagName("head")[0];
                                const script = document.createElement("script");
                                script.type = "text/javascript";
                                script.onreadystatechange = signalLoaded;
                                script.onload = signalLoaded;
                                script.src = ${Json.encodeToString(urlAsUri)};
                                head.appendChild(script);
                            })();
                            """.trimIndent()
                ) { value -> logger.d { "added app script tag" } }
            }
        } ?: error("WebView not initialized")
    }

    override suspend fun signalTimelineToken(callId: String, token: String) {
        val tokenJson = Json.encodeToString(mapOf("userToken" to token, "callId" to callId))
        withContext(Dispatchers.Main) {
            webView?.evaluateJavascript("window.signalTimelineTokenSuccess('${Uri.encode(tokenJson)}')", null)
        }
    }

    override suspend fun signalTimelineTokenFail(callId: String) {
        val tokenJson = Json.encodeToString(mapOf("userToken" to null, "callId" to callId))
        withContext(Dispatchers.Main) {
            webView?.evaluateJavascript("window.signalTimelineTokenFailure('${Uri.encode(tokenJson)}')", null)
        }
    }

    override suspend fun signalReady() {
        val readyDeviceIds = listOf(device.identifier.asString)
        val readyJson = Json.encodeToString(readyDeviceIds)
        withContext(Dispatchers.Main) {
            webView?.evaluateJavascript("window.signalReady(${readyJson})", null)
        }
        _readyState.value = true
    }

    override suspend fun signalNewAppMessageData(data: String?): Boolean {
        readyState.first { it }
        withContext(Dispatchers.Main) {
            webView?.evaluateJavascript("window.signalNewAppMessageData(${"'" + (data ?: "null") + "'"})", null)
        }
        return true
    }

    override suspend fun signalAppMessageAck(data: String?): Boolean {
        withContext(Dispatchers.Main) {
            webView?.evaluateJavascript("window.signalAppMessageAck(${"'" + (data ?: "null") + "'"})", null)
        }
        return true
    }

    override suspend fun signalAppMessageNack(data: String?): Boolean {
        withContext(Dispatchers.Main) {
            webView?.evaluateJavascript("window.signalAppMessageNack(${"'" + (data ?: "null") + "'"})", null)
        }
        return true
    }

    override suspend fun signalShowConfiguration() {
        readyState.first { it }
        withContext(Dispatchers.Main) {
            webView?.evaluateJavascript("window.signalShowConfiguration()", null)
        }
    }

    override suspend fun signalWebviewClosed(data: String?) {
        withContext(Dispatchers.Main) {
            webView?.evaluateJavascript("window.signalWebviewClosedEvent(${Json.encodeToString(data)})", null)
        }
    }

    override suspend fun eval(js: String) {
        withContext(Dispatchers.Main) {
            webView?.evaluateJavascript(js, null) ?: run {
                logger.e { "WebView not initialized, cannot evaluate JS" }
            }
        }
    }

    override suspend fun evalWithResult(js: String): Any? {
        return withContext(Dispatchers.Main) {
            return@withContext suspendCancellableCoroutine { cont ->
                webView?.evaluateJavascript(js) { result ->
                    cont.resume(result)
                } ?: cont.resumeWithException(IllegalStateException("WebView not initialized"))
            }
        }
    }
}
