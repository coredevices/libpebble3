package io.rebble.libpebblecommon.js

import co.touchlab.kermit.Logger
import io.rebble.cobble.shared.data.js.ActivePebbleWatchInfo
import io.rebble.cobble.shared.data.js.fromWatchInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

abstract class PrivatePKJSInterface(
    protected val jsRunner: JsRunner,
    private val device: PebbleJSDevice,
    protected val scope: CoroutineScope,
    private val outgoingAppMessages: MutableSharedFlow<Pair<CompletableDeferred<Byte>, String>>,
    private val logMessages: MutableSharedFlow<String>,
) {
    companion object {
        private val logger = Logger.withTag(PrivatePKJSInterface::class.simpleName!!)
        private val sensitiveTerms = setOf(
            "token", "password", "secret", "key", "credentials",
            "auth", "bearer", "lat", "lon", "location"
        )
    }

    open fun privateLog(message: String) {
        logger.i { "privateLog: $message" }
    }

    open fun onConsoleLog(level: String, message: String, source: String?) {
        logger.i {
            val containsSensitiveInfo = sensitiveTerms.any { term ->
                message.contains(term, ignoreCase = true)
            }
            buildString {
                append("[PKJS:${level.uppercase()}] \"")
                if (containsSensitiveInfo) {
                    append("<REDACTED>")
                } else {
                    append(message)
                }
                append("\" ")
                source?.let {
                    append(source)
                }
            }
        }
        val lineNumber = source
            ?.substringAfterLast("${jsRunner.appInfo.uuid}.js:", "")
            ?.ifBlank { null }
        lineNumber?.let {
            logMessages.tryEmit("${jsRunner.appInfo.longName}:$lineNumber $message")
        }
    }

    open fun onError(message: String?, source: String?, line: Double?, column: Double?) {
        logger.e { "JS Error: $message at $source:${line?.toInt()}:${column?.toInt()}" }
    }

    open fun onUnhandledRejection(reason: String) {
        logger.e { "Unhandled Rejection: $reason" }
    }

    open fun logInterceptedSend() {
        logger.v { "logInterceptedSend" }
    }

    open fun getVersionCode(): Int {
        logger.v { "getVersionCode" }
        TODO("Not yet implemented")
    }

    open fun getTimelineTokenAsync(): String {
        val uuid = Uuid.parse(jsRunner.appInfo.uuid)
        //TODO: Get token from locker or sandbox token if app is sideloaded
        scope.launch {
            jsRunner.signalTimelineTokenFail(uuid.toString())
        }
        return uuid.toString()
    }

    open fun signalAppScriptLoadedByBootstrap() {
        logger.v { "signalAppScriptLoadedByBootstrap" }
        scope.launch {
            jsRunner.signalReady()
        }
    }

    open fun sendAppMessageString(jsonAppMessage: String): Int {
        logger.v { "sendAppMessageString" }
        val completable = CompletableDeferred<Byte>()
        if (!outgoingAppMessages.tryEmit(Pair(completable, jsonAppMessage))) {
            logger.e { "Failed to emit outgoing AppMessage" }
            error("Failed to emit outgoing AppMessage")
        }
            return runBlocking {
                try {
                    withTimeout(10.seconds) {
                    completable.await().toInt()
                }
            } catch (_: TimeoutCancellationException) {
                logger.e { "Timeout while waiting for AppMessage ack" }
                -1
            }
        }
    }

    open fun getActivePebbleWatchInfo(): String {
        val info = device.watchInfo
        return Json.encodeToString(ActivePebbleWatchInfo.fromWatchInfo(info))
    }

    open fun privateFnConfirmReadySignal(success: Boolean) {
        logger.v { "privateFnConfirmReadySignal($success)" }
        //TODO: signalShowConfiguration() if needed
    }
}
