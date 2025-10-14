package io.rebble.libpebblecommon.js

import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.TokenProvider
import io.rebble.libpebblecommon.database.dao.FakeLockerEntryDao
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.io.files.Path

fun createJsRunner(
    libPebble: LibPebble,
    scope: CoroutineScope,
    appInfo: PbwAppInfo,
    lockerEntry: LockerEntry,
    jsPath: Path,
    device: PebbleJSDevice,
    urlOpenRequests: Channel<String>,
    logMessages: MutableSharedFlow<String>
): JsRunner {
    return JavascriptCoreJsRunner(
        appContext = AppContext(),
        libPebble = libPebble,
        jsTokenUtil = JsTokenUtil(
            object : TokenProvider {
                override suspend fun getDevToken(): String? {
                    return null
                }
            },
            lockerEntryDao = FakeLockerEntryDao()
        ),
        device = device,
        scope = scope,
        appInfo = appInfo,
        lockerEntry = lockerEntry,
        jsPath = jsPath,
        urlOpenRequests = urlOpenRequests,
        logMessages = logMessages,
        pkjsBundleIdentifier = null
    )
}