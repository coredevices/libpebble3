package io.rebble.libpebblecommon.web

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.connection.WebServices
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.services.WatchInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

interface FirmwareUpdateManager {
    fun init(watchInfo: WatchInfo)
    fun checkForUpdates()
    val availableUpdates: Flow<FirmwareUpdateCheckResult?>
}

class RealFirmwareUpdateManager(
    private val webServices: WebServices,
    private val connectionCoroutineScope: ConnectionCoroutineScope,
) : FirmwareUpdateManager {
    private val _availableUpdates = MutableStateFlow<FirmwareUpdateCheckResult?>(null)
    private val checkTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 5)
    private var watchInfo: WatchInfo? = null
    private val logger = Logger.withTag("FirmwareUpdateManager")

    companion object {
        private val INITIAL_DELAY_PERIOD = 1.seconds
    }

    override fun init(watchInfo: WatchInfo) {
        this.watchInfo = watchInfo
        connectionCoroutineScope.launch {
            checkTrigger.conflate().collect {
                doUpdateCheck()
            }
        }
        connectionCoroutineScope.launch {
            delay(INITIAL_DELAY_PERIOD)
        }
    }

    override fun checkForUpdates() {
        logger.d { "checkForUpdates" }
        checkTrigger.tryEmit(Unit)
    }

    private suspend fun doUpdateCheck() {
        val watch = watchInfo
        if (watch == null) {
            logger.e { "doUpdateCheck: watch is null!" }
            return
        }
        logger.d { "doUpdateCheck" }
        val firmwareUpdateAvailable = webServices.checkForFirmwareUpdate(watch)
        logger.d { "firmwareUpdateAvailable = $firmwareUpdateAvailable" }
        _availableUpdates.value = firmwareUpdateAvailable
    }

    override val availableUpdates: Flow<FirmwareUpdateCheckResult?> = _availableUpdates.asStateFlow()
}