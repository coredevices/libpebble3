package io.rebble.libpebblecommon.pebblekit

import android.content.Context
import android.content.IntentFilter
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.util.asFlow
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.uuid.toKotlinUuid

class PebbleKitClassicStartListeners(
    private val context: Context,
    private val libPebble: LibPebble,
    private val coroutineScope: LibPebbleCoroutineScope
) {
    companion object {
        private val logger = Logger.withTag(PebbleKitClassicStartListeners::class.simpleName!!)
    }

    fun init() {
        coroutineScope.launch {
            IntentFilter(INTENT_APP_START).asFlow(context, exported = true).collect { intent ->
                logger.v { "Got intent: $intent" }
                val uuid = intent.getSerializableExtra(APP_UUID) as UUID? ?: return@collect
                logger.d { "Got app start: $uuid" }
                libPebble.launchApp(uuid.toKotlinUuid())
            }
        }

        coroutineScope.launch {
            IntentFilter(INTENT_APP_STOP).asFlow(context, exported = true).collect { intent ->
                val uuid = intent.getSerializableExtra(APP_UUID) as UUID? ?: return@collect
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
