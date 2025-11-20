package io.rebble.libpebblecommon.pebblekit.two

import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.Watches
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.di.LibPebbleKoinComponent
import io.rebble.libpebblecommon.services.appmessage.AppMessageResult
import io.rebble.pebblekit2.common.model.PebbleDictionary
import io.rebble.pebblekit2.common.model.TransmissionResult
import io.rebble.pebblekit2.common.model.WatchIdentifier
import io.rebble.pebblekit2.server.BasePebbleSenderReceiver
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import java.util.UUID
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

private val WATCH_SENDING_TIMEOUT = 10.seconds

class PebbleSenderReceiver : BasePebbleSenderReceiver(), LibPebbleKoinComponent {
    private val watchManager: Watches = getKoin().get<LibPebble>()
    override val coroutineScope: LibPebbleCoroutineScope = getKoin().get()

    override suspend fun sendDataToPebble(
        callingPackage: String?,
        watchappUUID: UUID,
        data: PebbleDictionary,
        watches: List<WatchIdentifier>?
    ): Map<WatchIdentifier, TransmissionResult> {
        return runOnConnectedWatches(watches) { watch ->
            val companionApp = watch.currentCompanionAppSession.value as? PebbleKit2

            if (companionApp == null || companionApp.uuid.toJavaUuid() != watchappUUID) {
                return@runOnConnectedWatches TransmissionResult.FailedDifferentAppOpen
            }

            if (callingPackage == null ||
                !companionApp.isAllowedToCommunicate(callingPackage)
            ) {
                return@runOnConnectedWatches TransmissionResult.FailedNoPermissions
            }

            val res = companionApp.sendMessage(data)
            when (res) {
                is AppMessageResult.ACK -> TransmissionResult.Success
                is AppMessageResult.NACK -> TransmissionResult.FailedWatchNacked
            }
        }
    }

    override suspend fun startAppOnTheWatch(
        watchappUUID: UUID,
        watches: List<WatchIdentifier>?
    ): Map<WatchIdentifier, TransmissionResult> {
        return runOnConnectedWatches(watches) {
            it.launchApp(watchappUUID.toKotlinUuid())
            TransmissionResult.Success
        }
    }

    override suspend fun stopAppOnTheWatch(
        watchappUUID: UUID,
        watches: List<WatchIdentifier>?
    ): Map<WatchIdentifier, TransmissionResult> {
        return runOnConnectedWatches(watches) {
            it.stopApp(watchappUUID.toKotlinUuid())
            TransmissionResult.Success
        }
    }

    private inline suspend fun runOnConnectedWatches(
        watches: List<WatchIdentifier>?,
        crossinline action: suspend (ConnectedPebbleDevice) -> TransmissionResult
    ): Map<WatchIdentifier, TransmissionResult> {
        val connectedWatches = watchManager.watches.value.filterIsInstance<ConnectedPebbleDevice>()

        val targetWatches = if (watches == null) {
            connectedWatches.map { WatchIdentifier(it.watchInfo.serial) }
        } else {
            watches
        }

        return coroutineScope {
            targetWatches.associateWith { targetWatchId ->
                async {
                    val watch = connectedWatches.firstOrNull { it.serial == targetWatchId.value }
                        ?: return@async TransmissionResult.FailedWatchNotConnected

                    try {
                        withTimeout(WATCH_SENDING_TIMEOUT) {
                            action(watch)
                        }
                    } catch (e: TimeoutCancellationException) {
                        TransmissionResult.FailedTimeout
                    }
                }
            }.mapValues { it.value.await() }
        }
    }
}
