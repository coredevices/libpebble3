package io.rebble.libpebblecommon.js

import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.FakeAppMessages
import io.rebble.libpebblecommon.connection.FakeLibPebble
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.fakeWatch
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import io.rebble.libpebblecommon.metadata.pbw.appinfo.Resources
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

abstract class PKJSRunnerTests(
    private val createJsRunner: (
        libPebble: LibPebble,
        scope: CoroutineScope,
        appInfo: PbwAppInfo,
        lockerEntry: LockerEntry,
        jsPath: Path,
        device: CompanionAppDevice,
        urlOpenRequests: Channel<String>,
        logMessages: MutableSharedFlow<String>
    ) -> JsRunner
) {
    companion object {
        private val APPINFO = PbwAppInfo(
            uuid = Uuid.NIL.toString(),
            shortName = "Test App",
            versionLabel = "1.0",
            resources = Resources(emptyList()),
        )

        private val LOCKERENTRY = LockerEntry(
            id = Uuid.NIL,
            version = "1.0",
            title = "Test App",
            type = "watchapp",
            developerName = "Test Developer",
            configurable = false,
            pbwVersionCode = "1",
            platforms = emptyList(),
        )
    }

    private fun writeJS(js: String): Path {
        val tempDir = Path(SystemTemporaryDirectory, "pkjs-test")
        val jsPath = Path(tempDir, "${Uuid.random()}.js")
        SystemFileSystem.createDirectories(tempDir)
        SystemFileSystem.sink(jsPath).buffered().use {
            it.writeString(js)
        }
        return jsPath
    }

    private val logMessageFlow = MutableSharedFlow<String>().also {
        GlobalScope.launch {
            it.collect { msg ->
                println("JSLOG: $msg")
            }
        }
    }

    private fun makeRunner(
        js: String,
        uuid: Uuid,
        scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
        appMessages: FakeAppMessages = FakeAppMessages()
    ): JsRunner {
        val libPebble = FakeLibPebble()
        val watch = fakeWatch(connected = true) as ConnectedPebbleDevice
        return createJsRunner(
            libPebble,
            scope,
            APPINFO.copy(uuid = uuid.toString()),
            LOCKERENTRY.copy(id = uuid),
            writeJS(js),
            CompanionAppDevice(
                watch.identifier,
                watch.watchInfo,
                appMessages
            ),
            Channel(Channel.UNLIMITED),
            logMessageFlow,
        )
    }

    open fun testJSExecution() {
        val scope = CoroutineScope(Dispatchers.Default)
        val runner = makeRunner("", Uuid.random(), scope = scope)
        runBlocking {
            runner.start()
            runner.eval("window.test = true;")
            val result = runner.evalWithResult("window.test;")
            when (result) {
                is Boolean -> {
                    assertTrue(result)
                }
                is String -> {
                    assertEquals("true", result)
                }
                else -> {
                    error("Unexpected result type: ${result?.let { it::class }}")
                }
            }
        }
        assertTrue(scope.isActive)
    }

    open fun testJSReady() {
        val runner = makeRunner("""
            Pebble.addEventListener('ready', function() {
              window.readyConfirmed = true;
            });
        """.trimIndent(), Uuid.random())

        runBlocking {
            runner.start()
            // Wait a bit for the event to be processed
            withTimeout(1.seconds) {
                runner.readyState.first { it }
            }
            delay(5)
            val result = runner.evalWithResult("window.readyConfirmed;")
            when (result) {
                is Boolean -> {
                    assertTrue(result, "ready event handler should set window.readyConfirmed to true")
                }
                is String -> {
                    assertEquals("true", result, "ready event handler should set window.readyConfirmed to true")
                }
                else -> {
                    error("Unexpected result type: ${result?.let { it::class }}")
                }
            }
        }
    }

    open fun testLocalStoragePersistence() {
        val js = """
            Pebble.addEventListener('ready', function() {
                localStorage.setItem('testKey', 'testValue');
            });
        """.trimIndent()
        val uuid = Uuid.random()
        var runner = makeRunner(js, uuid)

        runBlocking {
            runner.start()
            withTimeout(1.seconds) {
                runner.readyState.first { it }
            }
            delay(5)
            runner.stop()

            runner = makeRunner("", uuid)
            runner.start()
            withTimeout(1.seconds) {
                runner.readyState.first { it }
            }
            delay(5)
            val result = runner.evalWithResult("localStorage.getItem('testKey');")
            when (result) {
                is String -> {
                    val result = Json.decodeFromString<JsonElement>(result)
                    assertEquals("testValue", result.jsonPrimitive.content)
                }
                else -> {
                    error("Unexpected result type: ${result?.let { it::class }}")
                }
            }
        }
    }

    open fun testLocalStorageSandbox() {
        val js = """
            Pebble.addEventListener('ready', function() {
                localStorage.setItem('testKey', 'testValue');
            });
        """.trimIndent()

        var runner = makeRunner(js, Uuid.random())

        runBlocking {
            runner.start()
            withTimeout(1.seconds) {
                runner.readyState.first { it }
            }
            delay(5)
            runner.stop()

            runner = makeRunner("", Uuid.random())
            runner.start()
            withTimeout(1.seconds) {
                runner.readyState.first { it }
            }
            delay(5)
            val result = runner.evalWithResult("localStorage.getItem('testKey');")
            when (result) {
                is String -> {
                    val result = Json.decodeFromString<JsonElement>(result)
                    assertEquals(JsonNull, result.jsonPrimitive)
                }
                else -> {
                    error("Unexpected result type: ${result?.let { it::class }}")
                }
            }
        }
    }

    /**
     * Testing for reproduction of undefined behaviour from the old app where localStorage is accessible
     * before the 'ready' event is fired.
     * see MOB-2364
     */
    open fun testLocalStorageEarlyExecution() {
        val uuid = Uuid.random()
        var runner = makeRunner("""
            window.overrideAtScriptTime = localStorage.__override__;
            localStorage.setItem('testKey', 'testValue');
        """.trimIndent(), uuid)

        runBlocking {
            runner.start()
            withTimeout(1.seconds) {
                runner.readyState.first { it }
            }
            delay(5)
            val resultEarlySet = runner.evalWithResult("localStorage.getItem('testKey');")
            when (resultEarlySet) {
                is String -> {
                    val result = Json.decodeFromString<JsonElement>(resultEarlySet)
                    assertEquals(
                        "testValue",
                        result.jsonPrimitive.content,
                        "Early localStorage.setItem should save data to shimmed persistent storage"
                    )
                }
                else -> {
                    error("Unexpected result type: ${resultEarlySet?.let { it::class }}")
                }
            }
            val overrideAtScriptTime = runner.evalWithResult("window.overrideAtScriptTime;")
            when (overrideAtScriptTime) {
                is Boolean -> {
                    assertTrue(overrideAtScriptTime, "localStorage.__override__ should be true at script execution time, indicating shim is in place")
                }
                is String -> {
                    assertEquals("\"true\"", overrideAtScriptTime, "localStorage.__override__ should be true at script execution time, indicating shim is in place")
                }
                else -> {
                    error("Unexpected result type: ${overrideAtScriptTime?.let { it::class }}")
                }
            }

            runner.stop()

            runner = makeRunner("window.result = localStorage.getItem('testKey');", uuid)
            runner.start()
            withTimeout(1.seconds) {
                runner.readyState.first { it }
            }
            delay(5)
            val resultEarlyGet = runner.evalWithResult("window.result;")
            when (resultEarlyGet) {
                is String -> {
                    val result = Json.decodeFromString<JsonElement>(resultEarlyGet)
                    assertEquals(
                        "testValue",
                        result.jsonPrimitive.content,
                        "Early localStorage.getItem should return persisted data from shimmed persistent storage"
                    )
                }
                else -> {
                    error("Unexpected result type: ${resultEarlyGet?.let { it::class }}")
                }
            }
        }
    }
}