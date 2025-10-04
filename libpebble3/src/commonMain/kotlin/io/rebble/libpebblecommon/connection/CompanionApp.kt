package io.rebble.libpebblecommon.connection

import kotlinx.coroutines.CoroutineScope

interface CompanionApp {
    suspend fun start(connectionScope: CoroutineScope)
    suspend fun stop()
}
