package io.rebble.libpebblecommon.pebblekit

import android.content.Context
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.Watches
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.di.LibPebbleKoinComponent
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class PebbleKitProviderNotifier : LibPebbleKoinComponent {
    fun init() {
        val watches: Watches = getKoin().get<LibPebble>()
        val libPebbleCoroutineScope: LibPebbleCoroutineScope = getKoin().get()
        val context: Context = getKoin().get()

        libPebbleCoroutineScope.launch {
            watches.watches.map {
                it.any { it is ConnectedPebbleDevice }
            }
                .distinctUntilChanged()
                .collect {
                    context.contentResolver.notifyChange(PebbleKitProvider.URI_CONTENT_BASALT, null)
                }
        }
    }
}
