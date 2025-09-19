package io.rebble.libpebblecommon.connection.endpointmanager

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.FirmwareUpdateCheckResult
import io.rebble.libpebblecommon.connection.PebbleIdentifier
import io.rebble.libpebblecommon.connection.endpointmanager.FirmwareUpdater.FirmwareUpdateStatus
import io.rebble.libpebblecommon.connection.endpointmanager.putbytes.PutBytesSession
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.disk.pbz.PbzFirmware
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import io.rebble.libpebblecommon.metadata.pbz.manifest.PbzManifest
import io.rebble.libpebblecommon.packets.ObjectType
import io.rebble.libpebblecommon.packets.SystemMessage
import io.rebble.libpebblecommon.services.FirmwareVersion
import io.rebble.libpebblecommon.services.PutBytesService
import io.rebble.libpebblecommon.services.SystemService
import io.rebble.libpebblecommon.web.FirmwareDownloader
import io.rebble.libpebblecommon.web.FirmwareUpdateManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlin.time.Instant

sealed class FirmwareUpdateException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {
    class SafetyCheckFailed(message: String) : FirmwareUpdateException(message)
    class TransferFailed(message: String, cause: Throwable?, val bytesTransferred: UInt) :
        FirmwareUpdateException(message, cause)
}

enum class FirmwareUpdateErrorStarting {
    ErrorDownloading,
    ErrorParsingPbz,
}

interface FirmwareUpdater : ConnectedPebble.FirmwareUpdate {
    val firmwareUpdateState: StateFlow<FirmwareUpdateStatus>
    fun setPlatform(watchPlatform: WatchHardwarePlatform)

    sealed class FirmwareUpdateStatus {
        sealed class NotInProgress : FirmwareUpdateStatus() {
            data object Idle : NotInProgress()
            data class ErrorStarting(val error: FirmwareUpdateErrorStarting) : NotInProgress()
        }

        sealed class Active : FirmwareUpdateStatus() {
            abstract val update: FirmwareUpdateCheckResult
        }

        data class WaitingToStart(override val update: FirmwareUpdateCheckResult) : Active()
        data class InProgress(
            override val update: FirmwareUpdateCheckResult,
            val progress: StateFlow<Float>,
        ) : Active()

        /**
         * Won't be in this state for long (we'll be disconnected very soon, at which point no-one
         * is looking at this state).
         */
        data class WaitingForReboot(override val update: FirmwareUpdateCheckResult) : Active()
    }
}

class RealFirmwareUpdater(
    identifier: PebbleIdentifier,
    private val systemService: SystemService,
    private val putBytesSession: PutBytesSession,
    private val firmwareDownloader: FirmwareDownloader,
    private val connectionCoroutineScope: ConnectionCoroutineScope,
    private val firmwareUpdateManager: FirmwareUpdateManager,
) : FirmwareUpdater {
    private val logger = Logger.withTag("FWUpdate-$identifier")
    private lateinit var watchPlatform: WatchHardwarePlatform
    private val _firmwareUpdateState =
        MutableStateFlow<FirmwareUpdateStatus>(FirmwareUpdateStatus.NotInProgress.Idle)
    override val firmwareUpdateState: StateFlow<FirmwareUpdateStatus> =
        _firmwareUpdateState.asStateFlow()

    override fun setPlatform(watchPlatform: WatchHardwarePlatform) {
        this.watchPlatform = watchPlatform
    }

    private fun performSafetyChecks(pbzFw: PbzFirmware) {
        val manifest = pbzFw.manifest
        when {
            manifest.firmware.type != "normal" && manifest.firmware.type != "recovery" ->
                throw FirmwareUpdateException.SafetyCheckFailed("Invalid firmware type: ${manifest.firmware.type}")

            manifest.firmware.crc <= 0L ->
                throw FirmwareUpdateException.SafetyCheckFailed("Invalid firmware CRC: ${manifest.firmware.crc}")

            manifest.firmware.size <= 0 ->
                throw FirmwareUpdateException.SafetyCheckFailed("Invalid firmware size: ${manifest.firmware.size}")

            manifest.resources != null && manifest.resources.size <= 0 ->
                throw FirmwareUpdateException.SafetyCheckFailed("Invalid resources size: ${manifest.resources.size}")

            manifest.resources != null && manifest.resources.crc <= 0L ->
                throw FirmwareUpdateException.SafetyCheckFailed("Invalid resources CRC: ${manifest.resources.crc}")

            watchPlatform != pbzFw.manifest.firmware.hwRev ->
                throw FirmwareUpdateException.SafetyCheckFailed("Firmware board does not match watch board: ${pbzFw.manifest.firmware.hwRev} != $watchPlatform")
        }
    }

    private suspend fun sendFirmwareParts(
        pbzFw: PbzFirmware,
        offset: UInt,
        update: FirmwareUpdateCheckResult,
    ) {
        var totalSent = 0u
        check(
            offset < (pbzFw.manifest.firmware.size + (pbzFw.manifest.resources?.size ?: 0)).toUInt()
        ) {
            "Resume offset greater than total transfer size"
        }
        var firmwareCookie: UInt? = null
        val progessFlow = MutableStateFlow(0.0f)
        if (offset < pbzFw.manifest.firmware.size.toUInt()) {
            try {
                sendFirmware(pbzFw, offset).collect {
                    when (it) {
                        is PutBytesSession.SessionState.Open -> {
                            logger.d { "PutBytes session opened for firmware" }
                            _firmwareUpdateState.value =
                                FirmwareUpdateStatus.InProgress(update, progessFlow)
                        }

                        is PutBytesSession.SessionState.Sending -> {
                            totalSent = it.totalSent
                            val progress =
                                (it.totalSent.toFloat() / pbzFw.manifest.firmware.size) / 2.0f
                            logger.i { "Firmware update progress: $progress (putbytes cookie: ${it.cookie})" }
                            progessFlow.emit(progress)
                        }

                        is PutBytesSession.SessionState.Finished -> {
                            firmwareCookie = it.cookie
                        }
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) {
                    logger.d { "Firmware transfer cancelled" }
                    throw e
                } else {
                    throw FirmwareUpdateException.TransferFailed(
                        "Failed to transfer firmware",
                        e,
                        totalSent
                    )
                }
            }
            logger.d { "Completed firmware transfer" }
        } else {
            logger.d { "Firmware already sent, skipping firmware PutBytes" }
        }
        var resourcesCookie: UInt? = null
        pbzFw.manifest.resources?.let { res ->
            val resourcesOffset = if (offset < pbzFw.manifest.firmware.size.toUInt()) {
                0u
            } else {
                offset - pbzFw.manifest.firmware.size.toUInt()
            }
            try {
                sendResources(pbzFw, resourcesOffset).collect {
                    when (it) {
                        is PutBytesSession.SessionState.Open -> {
                            logger.d { "PutBytes session opened for resources" }
                            progessFlow.emit(0.5f)
                        }

                        is PutBytesSession.SessionState.Sending -> {
                            totalSent = pbzFw.manifest.firmware.size.toUInt() + it.totalSent
                            val progress =
                                0.5f + ((it.totalSent.toFloat() / res.size.toFloat()) / 2.0f)
                            logger.i { "Resources update progress: $progress (putbytes cookie: ${it.cookie})" }
                            progessFlow.emit(progress)
                        }

                        is PutBytesSession.SessionState.Finished -> {
                            resourcesCookie = it.cookie
                        }
                    }
                }
            } catch (e: Exception) {
                throw FirmwareUpdateException.TransferFailed(
                    "Failed to transfer resources",
                    e,
                    totalSent
                )
            }
            logger.d { "Completed resources transfer" }
        } ?: logger.d { "No resources to send, resource PutBytes skipped" }

        // Install both right at the end after all transfers are complete (i.e. don't install
        // one without both having been successfully transferred).
        firmwareCookie?.let { putBytesSession.sendInstall(it) }
        resourcesCookie?.let { putBytesSession.sendInstall(it) }
    }

    private suspend fun tryStartUpdateMutex(update: FirmwareUpdateCheckResult): Boolean {
        startMutex.withLock {
            if (_firmwareUpdateState.value !is FirmwareUpdateStatus.NotInProgress) {
                logger.w { "Firmware update already in progress!" }
                return false
            }
            _firmwareUpdateState.value = FirmwareUpdateStatus.WaitingToStart(update)
            return true
        }
    }

    override fun sideloadFirmware(path: Path) {
        connectionCoroutineScope.launch {
            val pbz = PbzFirmware(path)
            val updateToVersion = pbz.manifest.asFirmwareVersion()
            if (updateToVersion == null) {
                logger.w { "Failed to parse firmware version to sideload from ${pbz.manifest}" }
                _firmwareUpdateState.value = FirmwareUpdateStatus.NotInProgress.ErrorStarting(
                    FirmwareUpdateErrorStarting.ErrorParsingPbz)
                return@launch
            }
            val update = FirmwareUpdateCheckResult(
                version = updateToVersion,
                url = "",
                notes = "Sideloaded",
            )
            logger.d { "sideloadFirmware path: $path" }
            if (!tryStartUpdateMutex(update)) {
                return@launch
            }
            beginFirmwareUpdate(pbz, 0u, update)
        }
    }

    override fun updateFirmware(update: FirmwareUpdateCheckResult) {
        connectionCoroutineScope.launch {
            logger.d { "updateFirmware: $update" }
            if (!tryStartUpdateMutex(update)) {
                return@launch
            }
            val path = firmwareDownloader.downloadFirmware(update.url)
            if (path == null) {
                _firmwareUpdateState.value = FirmwareUpdateStatus.NotInProgress.ErrorStarting(
                    FirmwareUpdateErrorStarting.ErrorDownloading)
                return@launch
            }
            val pbz = try {
                PbzFirmware(path)
            } catch (e: Exception) {
                logger.w(e) { "Failed to parse firmware: ${e.message}" }
                _firmwareUpdateState.value = FirmwareUpdateStatus.NotInProgress.ErrorStarting(
                    FirmwareUpdateErrorStarting.ErrorParsingPbz)
                return@launch
            }
            beginFirmwareUpdate(pbz, 0u, update)
        }
    }

    override fun checkforFirmwareUpdate() {
        firmwareUpdateManager.checkForUpdates()
    }

    private val startMutex = Mutex()

    private suspend fun beginFirmwareUpdate(
        pbzFw: PbzFirmware,
        offset: UInt,
        update: FirmwareUpdateCheckResult,
    ) {
        logger.d { "beginFirmwareUpdate" }
        try {
            val totalBytes = pbzFw.manifest.firmware.size + (pbzFw.manifest.resources?.size ?: 0)
            require(totalBytes > 0) { "Firmware size is 0" }
            performSafetyChecks(pbzFw)
            val result = systemService.sendFirmwareUpdateStart(offset, totalBytes.toUInt())
            if (result != SystemMessage.FirmwareUpdateStartStatus.Started) {
                error("Failed to start firmware update: $result")
            }
            sendFirmwareParts(pbzFw, offset, update)
            logger.d { "Firmware update completed, waiting for reboot" }
            _firmwareUpdateState.value = FirmwareUpdateStatus.WaitingForReboot(update)
            systemService.sendFirmwareUpdateComplete()
            return
        } catch (e: IllegalArgumentException) {
            logger.e(e) { "Firmware update failed: ${e.message}" }
        } catch (e: PutBytesService.PutBytesException) {
            logger.e(e) { "Firmware update failed: ${e.message}" }
        } catch (e: FirmwareUpdateException) {
            logger.e(e) { "Firmware update failed: ${e.message}" }
        } catch (e: IllegalStateException) {
            logger.e(e) { "Firmware update failed: ${e.message}" }
        } catch (e: Exception) {
            logger.e(e) { "Firmware update failed (unknown): ${e.message}" }
        }
        // Only if we hit an exception (i.e. we deliverately leave it in WaitingForReboot above if
        // completed successfully).
        _firmwareUpdateState.value = FirmwareUpdateStatus.NotInProgress.Idle
    }

    private fun sendFirmware(
        pbzFw: PbzFirmware,
        skip: UInt = 0u,
    ): Flow<PutBytesSession.SessionState> {
        val firmware = pbzFw.manifest.firmware
        val source = pbzFw.getFile(firmware.name).buffered()
        if (skip > 0u) {
            source.skip(skip.toLong())
        }
        return putBytesSession.beginSession(
            size = firmware.size.toUInt(),
            type = when (firmware.type) {
                "normal" -> ObjectType.FIRMWARE
                "recovery" -> ObjectType.RECOVERY
                else -> error("Unknown firmware type: ${firmware.type}")
            },
            bank = 0u,
            filename = "",
            source = source,
            sendInstall = false,
        ).onCompletion { source.close() } // Can't do use block because of the flow
    }

    private fun sendResources(
        pbzFw: PbzFirmware,
        skip: UInt = 0u,
    ): Flow<PutBytesSession.SessionState> {
        val resources = pbzFw.manifest.resources
            ?: throw IllegalArgumentException("Resources not found in firmware manifest")
        require(resources.size > 0) { "Resources size is 0" }
        val source = pbzFw.getFile(resources.name).buffered()
        if (skip > 0u) {
            source.skip(skip.toLong())
        }
        return putBytesSession.beginSession(
            size = resources.size.toUInt(),
            type = ObjectType.SYSTEM_RESOURCE,
            bank = 0u,
            filename = "",
            source = source,
            sendInstall = false,
        ).onCompletion { source.close() }
    }
}

fun PbzManifest.asFirmwareVersion(): FirmwareVersion? {
    val versionTag = firmware.versionTag
    if (versionTag == null) {
        Logger.w { "Firmware version tag is null" }
        return null
    }
    return FirmwareVersion.from(
        tag = versionTag,
        isRecovery = firmware.type == "recovery",
        gitHash = "",
        timestamp = Instant.fromEpochMilliseconds(firmware.timestamp),
        isDualSlot = false, // FIXME
        isSlot0 = false, // FIXME
    )
}