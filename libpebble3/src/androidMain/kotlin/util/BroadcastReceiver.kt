package io.rebble.libpebblecommon.util

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Consume intents from specific IntentFilter as coroutine flow
 * @param context Context to register the BroadcastReceiver with
 * @param exported Whether the receiver should be exported or not.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun IntentFilter.asFlow(context: Context, exported: Boolean): Flow<Intent> = callbackFlow {
    val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            trySend(intent).isSuccess
        }
    }

    ContextCompat.registerReceiver(
        context,
        receiver,
        this@asFlow,
        when (exported) {
            true -> ContextCompat.RECEIVER_EXPORTED
            false -> ContextCompat.RECEIVER_NOT_EXPORTED
        },
    )

    awaitClose {
        try {
            context.unregisterReceiver(receiver)
        } catch (e: IllegalArgumentException) {
            // unregisterReceiver can throw IllegalArgumentException if receiver
            // was already unregistered
            // This is not a problem, we can eat the exception
        }

    }
}
