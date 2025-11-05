package io.rebble.libpebblecommon.connection

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.services.SystemService
import io.rebble.libpebblecommon.services.WatchInfo
import io.rebble.libpebblecommon.services.app.AppRunStateService
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds

class Negotiator() {
    private val logger = Logger.withTag("Negotiator")

    suspend fun negotiate(
        systemService: SystemService,
        appRunStateService: AppRunStateService,
    ): WatchInfo? = try {
        Logger.d("negotiate()")
        withTimeout(20.seconds) {
            val appVersionRequest = systemService.appVersionRequest.await()
            logger.d("appVersionRequest = $appVersionRequest")
            systemService.sendPhoneVersionResponse()
            logger.d("sent watch version request")
            val watchInfo = systemService.requestWatchVersion()
            logger.d("watchVersionResponse = $watchInfo")
            systemService.updateTime()
            if (!watchInfo.runningFwVersion.isRecovery) {
                appRunStateService.waitForInitialAppRunState()
            }
            val runningApp = appRunStateService.runningApp.first()
            logger.d("runningApp = $runningApp")
            watchInfo
        }
    } catch (e: TimeoutCancellationException) {
        logger.w("negotiation timed out")
        null
    }
}