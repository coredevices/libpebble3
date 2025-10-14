package io.rebble.libpebblecommon.js

import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
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
import org.junit.Test

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
    val context = InstrumentationRegistry.getInstrumentation().context
    return WebViewJsRunner(
        appContext = AppContext(context),
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
        appInfo = appInfo,
        lockerEntry = lockerEntry,
        jsPath = jsPath,
        urlOpenRequests = urlOpenRequests,
        logMessages = logMessages,
        scope = scope
    )
}

@MediumTest
class PKJSRunnerTestsAndroid: PKJSRunnerTests(::createJsRunner) {
    @Test
    override fun testJSExecution() {
        super.testJSExecution()
    }

    @Test
    override fun testJSReady() {
        super.testJSReady()
    }

    @Test
    override fun testLocalStoragePersistence() {
        super.testLocalStoragePersistence()
    }

    @Test
    override fun testLocalStorageSandbox() {
        super.testLocalStorageSandbox()
    }

    @Test
    override fun testLocalStorageEarlyExecution() {
        super.testLocalStorageEarlyExecution()
    }
}