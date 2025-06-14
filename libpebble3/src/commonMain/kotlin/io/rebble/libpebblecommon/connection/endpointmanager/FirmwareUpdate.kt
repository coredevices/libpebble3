package io.rebble.libpebblecommon.connection.endpointmanager

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.Transport
import io.rebble.libpebblecommon.connection.endpointmanager.putbytes.PutBytesSession
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.disk.pbz.PbzFirmware
import io.rebble.libpebblecommon.metadata.WatchHardwarePlatform
import io.rebble.libpebblecommon.packets.ObjectType
import io.rebble.libpebblecommon.packets.SystemMessage
import io.rebble.libpebblecommon.services.SystemService
import io.rebble.libpebblecommon.web.FirmwareDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.buffered
import kotlinx.io.files.Path

sealed class FirmwareUpdateException(message: String, cause: Throwable? = null) :
    Exception(message, cause) {
    class SafetyCheckFailed(message: String) : FirmwareUpdateException(message)
    class TransferFailed(message: String, cause: Throwable?, val bytesTransferred: UInt) :
        FirmwareUpdateException(message, cause)
}

class FirmwareUpdate(
    transport: Transport,
    private val watchDisconnected: Deferred<Unit>,
    private val systemService: SystemService,
    private val putBytesSession: PutBytesSession,
    private val firmwareDownloader: FirmwareDownloader,
    private val libPebbleCoroutineScope: LibPebbleCoroutineScope,
    private val connectionCoroutineScope: ConnectionCoroutineScope,
) : ConnectedPebble.Firmware {
    private val logger = Logger.withTag("FWUpdate-${transport.name}")
    private lateinit var watchPlatform: WatchHardwarePlatform

    sealed class FirmwareUpdateStatus {
        data object WaitingToStart : FirmwareUpdateStatus()
        data class InProgress(val progress: Float) : FirmwareUpdateStatus()
        data object WaitingForReboot : FirmwareUpdateStatus()
        data object ErrorStarting : FirmwareUpdateStatus()
    }

    fun setPlatform(watchPlatform: WatchHardwarePlatform) {
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

    private suspend fun MutableSharedFlow<FirmwareUpdateStatus>.sendFirmwareParts(
        pbzFw: PbzFirmware,
        offset: UInt
    ) {
        var totalSent = 0u
        check(
            offset < (pbzFw.manifest.firmware.size + (pbzFw.manifest.resources?.size ?: 0)).toUInt()
        ) {
            "Resume offset greater than total transfer size"
        }
        var firmwareCookie: UInt? = null
        if (offset < pbzFw.manifest.firmware.size.toUInt()) {
            try {
                sendFirmware(pbzFw, offset).collect {
                    when (it) {
                        is PutBytesSession.SessionState.Open -> {
                            logger.d { "PutBytes session opened for firmware" }
                            emit(FirmwareUpdateStatus.InProgress(0f))
                        }

                        is PutBytesSession.SessionState.Sending -> {
                            totalSent = it.totalSent
                            val progress =
                                (it.totalSent.toFloat() / pbzFw.manifest.firmware.size) / 2.0f
                            logger.i { "Firmware update progress: $progress (putbytes cookie: ${it.cookie})" }
                            emit(FirmwareUpdateStatus.InProgress(progress))
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
                            emit(FirmwareUpdateStatus.InProgress(0.5f))
                        }

                        is PutBytesSession.SessionState.Sending -> {
                            totalSent = pbzFw.manifest.firmware.size.toUInt() + it.totalSent
                            val progress =
                                0.5f + ((it.totalSent.toFloat() / res.size.toFloat()) / 2.0f)
                            logger.i { "Resources update progress: $progress (putbytes cookie: ${it.cookie})" }
                            emit(FirmwareUpdateStatus.InProgress(progress))
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

    override fun updateFirmware(path: Path): Flow<FirmwareUpdateStatus> {
        val flow = MutableSharedFlow<FirmwareUpdateStatus>()
        connectionCoroutineScope.launch {
            beginFirmwareUpdate(PbzFirmware(path), 0u, flow)
        }
        return flow.asSharedFlow()
    }

    override fun updateFirmware(url: String): Flow<FirmwareUpdateStatus> {
        val flow = MutableSharedFlow<FirmwareUpdateStatus>()
        connectionCoroutineScope.launch {
            val path = firmwareDownloader.downloadFirmware(url)
            if (path == null) {
                flow.emit(FirmwareUpdateStatus.ErrorStarting)
                return@launch
            }
            beginFirmwareUpdate(PbzFirmware(path), 0u, flow)
        }
        return flow.asSharedFlow()
    }

    private suspend fun beginFirmwareUpdate(
        pbzFw: PbzFirmware,
        offset: UInt,
        flow: MutableSharedFlow<FirmwareUpdateStatus>,
    ) {
        val totalBytes = pbzFw.manifest.firmware.size + (pbzFw.manifest.resources?.size ?: 0)
        require(totalBytes > 0) { "Firmware size is 0" }
        performSafetyChecks(pbzFw)
        flow.emit(FirmwareUpdateStatus.WaitingToStart)
        val result = systemService.sendFirmwareUpdateStart(offset, totalBytes.toUInt())
        if (result != SystemMessage.FirmwareUpdateStartStatus.Started) {
            error("Failed to start firmware update: $result")
        }
        flow.sendFirmwareParts(pbzFw, offset)
        logger.d { "Firmware update completed, waiting for reboot" }
        flow.emit(FirmwareUpdateStatus.WaitingForReboot)
        systemService.sendFirmwareUpdateComplete()
        withContext(libPebbleCoroutineScope.coroutineContext) {
            withTimeout(60_000) {
                watchDisconnected.await()
            }
        }
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