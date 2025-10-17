package io.rebble.libpebblecommon.services.app

import io.rebble.libpebblecommon.connection.ConnectedPebble
import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.packets.AppRunStateMessage
import io.rebble.libpebblecommon.services.ProtocolService
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.uuid.Uuid

class AppRunStateService(
    private val protocolHandler: PebbleProtocolHandler,
    private val scope: ConnectionCoroutineScope,
) : ProtocolService,
    ConnectedPebble.AppRunState {
    private val _runningApp = MutableStateFlow<Uuid?>(null)
    override val runningApp: StateFlow<Uuid?> = _runningApp

    override suspend fun launchApp(uuid: Uuid) {
        protocolHandler.send(AppRunStateMessage.AppRunStateStart(uuid))
        runningApp.first { it == uuid }
    }

    override suspend fun stopApp(uuid: Uuid) {
        protocolHandler.send(AppRunStateMessage.AppRunStateStop(uuid))
    }

    // Ideally only called right after negotiation, further updates will be sent unprompted via flow
    suspend fun refreshAppRunState(): Uuid? {
        val result = scope.async { runningApp.drop(1).first() }
        protocolHandler.send(AppRunStateMessage.AppRunStateRequest())
        return result.await()
    }

    fun init() {
        protocolHandler.inboundMessages.onEach { packet ->
            when (packet) {
                is AppRunStateMessage.AppRunStateStart ->
                    _runningApp.value = packet.uuid.get()

                is AppRunStateMessage.AppRunStateStop ->
                    _runningApp.value = null
            }
        }.launchIn(scope)
    }
}
