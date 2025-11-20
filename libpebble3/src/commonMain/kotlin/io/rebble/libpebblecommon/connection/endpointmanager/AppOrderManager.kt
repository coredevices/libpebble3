package io.rebble.libpebblecommon.connection.endpointmanager

import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import com.russhwolf.settings.set
import io.rebble.libpebblecommon.SystemAppIDs.KICKSTART_APP_UUID
import io.rebble.libpebblecommon.SystemAppIDs.TICTOC_APP_UUID
import io.rebble.libpebblecommon.WatchConfigFlow
import io.rebble.libpebblecommon.connection.PebbleIdentifier
import io.rebble.libpebblecommon.database.dao.LockerEntryRealDao
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.locker.AppType
import io.rebble.libpebblecommon.packets.AppReorderRequest
import io.rebble.libpebblecommon.services.AppReorderService
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

class AppOrderManager(
    identifier: PebbleIdentifier,
    private val settings: Settings,
    private val lockerDao: LockerEntryRealDao,
    private val connectionScope: ConnectionCoroutineScope,
    private val watchConfigFlow: WatchConfigFlow,
    private val service: AppReorderService,
    private val json: Json,
) {
    private val logger = Logger.withTag("AppOrderManager")
    private val settingsKey = "app_order_-${identifier.asString}"
    private var stored: AppOrder = settings.getStringOrNull(settingsKey)?.let {
        json.decodeFromString(it)
    } ?: AppOrder(emptyList(), emptyList())

    fun init() {
        connectionScope.launch {
            lockerDao.getAppOrderFlow(
                type = AppType.Watchapp.code,
                limit = watchConfigFlow.value.lockerSyncLimit,
            ).distinctUntilChanged().collect { newOrder ->
                if (newOrder != stored.watchapps) {
                    stored = stored.copy(watchapps = newOrder)
                    updateOrder()
                }
            }
        }
        connectionScope.launch {
            lockerDao.getAppOrderFlow(
                type = AppType.Watchface.code,
                limit = watchConfigFlow.value.lockerSyncLimit,
            ).distinctUntilChanged().collect {
                val newOrder = SYSTEM_FACES + it
                if (newOrder != stored.watchfaces) {
                    stored = stored.copy(watchfaces = newOrder)
                    updateOrder()
                }
            }
        }
    }

    private suspend fun updateOrder() {
        logger.d { "Sending app order update: $stored" }
        service.send(AppReorderRequest(stored.watchapps + stored.watchfaces))
        settings.set(settingsKey, json.encodeToString(stored))
    }

    companion object {
        private val SYSTEM_FACES = listOf<Uuid>(
            TICTOC_APP_UUID,
            KICKSTART_APP_UUID,
        )
    }
}

@Serializable
private data class AppOrder(
    val watchfaces: List<Uuid>,
    val watchapps: List<Uuid>,
)
