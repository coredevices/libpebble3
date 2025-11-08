package io.rebble.libpebblecommon.connection.endpointmanager

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.CompanionApp
import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.PebbleIdentifier
import io.rebble.libpebblecommon.database.dao.LockerEntryRealDao
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.disk.pbw.PbwApp
import io.rebble.libpebblecommon.js.CompanionAppDevice
import io.rebble.libpebblecommon.js.PKJSApp
import io.rebble.libpebblecommon.locker.Locker
import io.rebble.libpebblecommon.locker.LockerPBWCache
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import io.rebble.libpebblecommon.services.WatchInfo
import io.rebble.libpebblecommon.services.app.AppRunStateService
import io.rebble.libpebblecommon.services.appmessage.AppMessageService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlin.coroutines.cancellation.CancellationException

class CompanionAppLifecycleManager(
    private val lockerPBWCache: LockerPBWCache,
    private val lockerEntryDao: LockerEntryRealDao,
    private val appRunStateService: AppRunStateService,
    private val appMessagesService: AppMessageService,
    private val locker: Locker,
    private val scope: ConnectionCoroutineScope
): ConnectedPebble.PKJS, ConnectedPebble.CompanionAppControl {
    companion object {
        private val logger = Logger.withTag(CompanionAppLifecycleManager::class.simpleName!!)
    }

    private lateinit var device: CompanionAppDevice


    private val runningApp: MutableStateFlow<CompanionApp?> = MutableStateFlow(null)
    @Deprecated("Use more generic currentCompanionAppSession instead and cast if necessary")
    override val currentPKJSSession: StateFlow<PKJSApp?> = PKJSStateFlow(runningApp)

    override val currentCompanionAppSession: StateFlow<CompanionApp?>
        get() = runningApp.asStateFlow()

    private suspend fun handleAppStop() {
        runningApp.value?.stop()
        runningApp.value = null
    }

    private suspend fun handleNewRunningApp(lockerEntry: LockerEntry, scope: CoroutineScope) {
        try {
            val pbw = PbwApp(lockerPBWCache.getPBWFileForApp(lockerEntry.id, lockerEntry.version, locker))
            if (runningApp.value != null) {
                logger.w { "App ${lockerEntry.id} is already running, stopping it before starting a new one" }
                runningApp.value?.stop()
            }

            runningApp.value = createCompanionApp(pbw, lockerEntry).also {
                it?.start(scope)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(e) { "Failed to init Companion app for app ${lockerEntry.id}: ${e.message}" }
            runningApp.value = null
            return
        }
    }

    private fun createCompanionApp(
        pbw: PbwApp,
        lockerEntry: LockerEntry
    ): CompanionApp? {
        return when {
            pbw.hasPKJS -> {
                val jsPath = lockerPBWCache.getPKJSFileForApp(lockerEntry.id, lockerEntry.version)
                PKJSApp(
                    device,
                    jsPath,
                    pbw.info,
                    lockerEntry,
                )
            }
            else -> {
                logger.v { "App ${lockerEntry.id} does not have a PKJS, falling back to platform based PebbleKit" }
                createPlatformSpecificCompanionAppControl(device, pbw.info)
            }
        }
    }

    fun init(identifier: PebbleIdentifier, watchInfo: WatchInfo) {
        this.device = CompanionAppDevice(
            identifier,
            watchInfo,
            appMessagesService
        )
        appRunStateService.runningApp.onEach {
            handleAppStop()
            if (it != null) {
                val lockerEntry = lockerEntryDao.getEntry(it)
                lockerEntry?.let { handleNewRunningApp(lockerEntry, scope) }
            }
        }.onCompletion {
            // Unsure if this is needed
            handleAppStop()
        }.launchIn(scope)
    }
}

/**
 * Hack to keep backwards compatibilty with the old ConnectedPebble.PKJS interface. It creates a state flow that only
 * exposes PKJSApp instances
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
class PKJSStateFlow(private val runningAppStateFlow: StateFlow<CompanionApp?>): StateFlow<PKJSApp?> {
    override val value: PKJSApp?
        get() = runningAppStateFlow.value as? PKJSApp
    override val replayCache: List<PKJSApp?>
        get() = runningAppStateFlow.replayCache.map { it as? PKJSApp }

    override suspend fun collect(collector: FlowCollector<PKJSApp?>): Nothing {
        runningAppStateFlow.map { it as? PKJSApp }.collect(collector)
        throw IllegalStateException("This collect should never stop because parent is a state flow")
    }
}

expect fun createPlatformSpecificCompanionAppControl(device: CompanionAppDevice, appInfo: PbwAppInfo): CompanionApp?
