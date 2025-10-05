package io.rebble.libpebblecommon.pebblekit

import android.content.Context
import android.content.IntentFilter
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.di.LibPebbleKoinComponent
import io.rebble.libpebblecommon.util.asFlow
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.uuid.toKotlinUuid

class PebbleKitClassicStartListeners() :
    LibPebbleKoinComponent {
    companion object {
        private val logger = Logger.withTag(PebbleKitClassicStartListeners::class.simpleName!!)
    }

    private val context: Context = getKoin().get()
    private val libPebble: LibPebble = getKoin().get()
    private val connectionScope: LibPebbleCoroutineScope = getKoin().get()


    fun init() {
        connectionScope.launch {
            IntentFilter(INTENT_APP_START).asFlow(context).collect { intent ->
                logger.v { "Got intent: $intent" }
                val uuid = intent.getSerializableExtra(APP_UUID, UUID::class.java) ?: return@collect
                logger.d { "Got app start: $uuid" }
                libPebble.launchApp(uuid.toKotlinUuid())
            }
        }

        connectionScope.launch {
            IntentFilter(INTENT_APP_STOP).asFlow(context).collect { intent ->
                val uuid = intent.getSerializableExtra(APP_UUID, UUID::class.java) ?: return@collect
                logger.d { "Got app stop: $uuid" }
                libPebble.stopApp(uuid.toKotlinUuid())
            }
        }
    }
}

/**
 * Intent broadcast to pebble.apk responsible for launching a watch-app on the connected watch. This intent is
 * idempotent.
 */
private const val INTENT_APP_START = "com.getpebble.action.app.START"

/**
 * Intent broadcast to pebble.apk responsible for closing a running watch-app on the connected watch. This intent is
 * idempotent.
 */
private const val INTENT_APP_STOP = "com.getpebble.action.app.STOP"


/**
 * The bundle-key used to store a message's UUID.
 */
private const val APP_UUID = "uuid"
