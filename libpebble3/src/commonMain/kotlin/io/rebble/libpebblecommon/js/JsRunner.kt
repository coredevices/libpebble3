package io.rebble.libpebblecommon.js

import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import io.rebble.libpebblecommon.services.appmessage.AppMessageResult
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.io.files.Path

abstract class JsRunner(
    val appInfo: PbwAppInfo,
    val lockerEntry: LockerEntry,
    val jsPath: Path,
    val device: CompanionAppDevice,
    private val urlOpenRequests: Channel<String>,
) {
    abstract suspend fun start()
    abstract suspend fun stop()
    abstract suspend fun loadAppJs(jsUrl: String)
    abstract suspend fun signalNewAppMessageData(data: String?): Boolean
    abstract suspend fun signalAppMessageAck(data: String?): Boolean
    abstract suspend fun signalAppMessageNack(data: String?): Boolean
    abstract suspend fun signalTimelineToken(callId: String, token: String)
    abstract suspend fun signalTimelineTokenFail(callId: String)
    abstract suspend fun signalReady()
    abstract suspend fun signalShowConfiguration()
    abstract suspend fun signalWebviewClosed(data: String?)
    abstract suspend fun eval(js: String)
    abstract suspend fun evalWithResult(js: String): Any?

    fun onReadyConfirmed(success: Boolean) {
        _readyState.value = true
    }

    suspend fun loadUrl(url: String) {
        urlOpenRequests.trySend(url)
    }

    protected val _outgoingAppMessages = MutableSharedFlow<AppMessageRequest>(extraBufferCapacity = 1)
    val outgoingAppMessages = _outgoingAppMessages.asSharedFlow()
    protected val _readyState = MutableStateFlow(false)
    val readyState = _readyState.asStateFlow()
}

class AppMessageRequest(
    val data: String
) {
    sealed class State {
        object Pending : State()
        object DataError : State()
        data class TransactionId(val transactionId: UByte) : State()
        data class Sent(val result: AppMessageResult) : State()
    }
    val state = MutableStateFlow<State>(State.Pending)
}
