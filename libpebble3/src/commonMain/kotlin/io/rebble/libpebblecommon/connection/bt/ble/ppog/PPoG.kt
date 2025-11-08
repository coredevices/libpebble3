package io.rebble.libpebblecommon.connection.bt.ble.ppog

import androidx.compose.ui.util.fastForEachReversed
import co.touchlab.kermit.Logger
import io.ktor.utils.io.writeByteArray
import io.rebble.libpebblecommon.BleConfigFlow
import io.rebble.libpebblecommon.connection.ConnectionException
import io.rebble.libpebblecommon.connection.ConnectionFailureReason
import io.rebble.libpebblecommon.connection.PebbleProtocolStreams
import io.rebble.libpebblecommon.connection.bt.ble.BlePlatformConfig
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min
import kotlin.time.Duration.Companion.seconds

interface PPoGPacketSender {
    suspend fun sendPacket(packet: ByteArray): Boolean
    fun wasRestoredWithSubscribedCentral(): Boolean
}

class PPoGStream(val inboundPPoGBytesChannel: Channel<ByteArray> = Channel(capacity = 100))

class PPoG(
    private val pebbleProtocolStreams: PebbleProtocolStreams,
    private val pPoGStream: PPoGStream,
    private val pPoGPacketSender: PPoGPacketSender,
    private val bleConfig: BleConfigFlow,
    private val blePlatformConfig: BlePlatformConfig,
    private val scope: ConnectionCoroutineScope,
) {
    private val logger = Logger.withTag("PPoG")
    private var mtu: Int = blePlatformConfig.initialMtu
    private var closed = false

    fun run() {
        scope.launch {
            val params = withTimeoutOrNull(12.seconds) {
                initWaitingForResetRequest()
            } ?: withTimeoutOrNull(5.seconds) {
                if (blePlatformConfig.fallbackToResetRequest) {
                    initWithResetRequest()
                } else {
                    null
                }
            } ?: throw ConnectionException(ConnectionFailureReason.TimeoutInitializingPpog)
            runConnection(params)
        }
    }

    private fun verboseLog(message: () -> String) {
        if (bleConfig.value.verbosePpogLogging) {
            logger.v(message = message)
        }
    }

    suspend fun close() {
        if (closed) return
        closed = true
        logger.d("close")
        if (blePlatformConfig.sendPpogResetOnDisconnection) {
            // This really for iOS, where the "connection" will stay alive when the app "disconnects",
            // but we need to get the watch's PPoG state machine into a "need to reconnect" state.
            sendPacketImmediately(PPoGPacket.ResetRequest(0, PPoGVersion.ONE), PPoGVersion.ONE)
        }
    }

    private suspend inline fun <reified T : PPoGPacket> waitForPacket(): T {
        // Wait for reset request from watch
        while (true) {
            val packet = pPoGStream.inboundPPoGBytesChannel.receive().asPPoGPacket()
            if (packet !is T) {
                // Not expected, but can happen (i.e. don't crash out): if a watch reconnects
                // really quickly then we can see stale packets come through.
                logger.w("unexpected packet $packet waiting for ${T::class}")
                continue
            }
            return packet
        }
    }

    private suspend fun initWithResetRequest(): PPoGConnectionParams? {
        logger.d("initWithResetRequest")
        // Reversed PPoG doesn't have a meta characteristic, so we have to assume.
        val ppogVersion = PPoGVersion.ONE

        // Send reset request
        try {
            sendPacketImmediately(
                packet = PPoGPacket.ResetRequest(
                    sequence = 0,
                    ppogVersion = ppogVersion,
                ),
                version = ppogVersion,
            )
        } catch (e: CancellationException) {
            throw  e
        } catch (e: Exception) {
            logger.e("error sending reset request", e)
        }

        val resetComplete = waitForPacket<PPoGPacket.ResetComplete>()
        logger.d("got $resetComplete")

        sendPacketImmediately(resetComplete, ppogVersion)

        return PPoGConnectionParams(
            rxWindow = resetComplete.rxWindow,
            txWindow = resetComplete.txWindow,
            pPoGversion = ppogVersion,
        )
    }

    // Negotiate connection
    private suspend fun initWaitingForResetRequest(): PPoGConnectionParams {
        logger.d("initWaitingForResetRequest")

        val resetRequest = waitForPacket<PPoGPacket.ResetRequest>()
        logger.d("got $resetRequest")

        // Send reset complete
        sendPacketImmediately(
            packet = PPoGPacket.ResetComplete(
                sequence = 0,
                rxWindow = min(blePlatformConfig.desiredRxWindow, MAX_SUPPORTED_WINDOW_SIZE),
                txWindow = min(blePlatformConfig.desiredTxWindow, MAX_SUPPORTED_WINDOW_SIZE),
            ),
            version = resetRequest.ppogVersion
        )

        // Wait for reset complete confirmation
        val resetComplete = pPoGStream.inboundPPoGBytesChannel.receive().asPPoGPacket()
        if (resetComplete !is PPoGPacket.ResetComplete) throw IllegalStateException("expected ResetComplete got $resetComplete")
        logger.d("got $resetComplete")

        return PPoGConnectionParams(
            rxWindow = min(min(resetComplete.txWindow, blePlatformConfig.desiredTxWindow), MAX_SUPPORTED_WINDOW_SIZE),
            txWindow = min(min(resetComplete.rxWindow, blePlatformConfig.desiredRxWindow), MAX_SUPPORTED_WINDOW_SIZE),
            pPoGversion = resetRequest.ppogVersion,
        )
    }

    // No need for any locking - state is only accessed/mutated within this method (except for mtu
    // which can only increase).
    private suspend fun runConnection(params: PPoGConnectionParams) {
        logger.d("runConnection: $params")

        val outboundSequence = Sequence()
        val inboundSequence = Sequence()
        val outboundDataQueue = ArrayDeque<PacketToSend>()
        val inflightPackets = ArrayDeque<PacketToSend>()
        val onTimeout = Channel<Unit>()
        var timeoutJob: Job? = null
        var lastSentAck: PPoGPacket.Ack? = null
        var lastReceivedAck: PPoGPacket.Ack? = null

        fun cancelTimeout() {
            timeoutJob?.cancel()
            timeoutJob = null
        }

        fun rescheduleTimeout() {
            cancelTimeout()
            timeoutJob = scope.launch {
                delay(RESET_REQUEST_TIMEOUT)
                logger.w("Packet timeout")
                onTimeout.send(Unit)
                timeoutJob = null
            }
        }

        fun resendInflightPackets() {
            inflightPackets.fastForEachReversed { packet ->
                val resendPacket = packet.copy(attemptCount = packet.attemptCount + 1)
                if (resendPacket.attemptCount > MAX_NUM_RETRIES) {
                    logger.w("Exceeded max retries")
                    throw IllegalStateException("Exceeded max retries")
                }
                outboundDataQueue.addFirst(resendPacket)
            }
            inflightPackets.clear()
        }

        fun removeResendsUpTo(sequence: Int) {
            while (true) {
                val sendPacket = outboundDataQueue.firstOrNull()
                if (sendPacket == null || sendPacket.attemptCount == 0 || sendPacket.packet.sequence > sequence) {
                    break
                }
                outboundDataQueue.removeFirst()
            }
        }

        while (true) {
            if (closed) return
            select {
                onTimeout.onReceive {
                    resendInflightPackets()
                }
                pebbleProtocolStreams.outboundPPBytes.onReceive { bytes ->
                    bytes.asList().chunked(maxDataBytes())
                        .map { chunk ->
                            PacketToSend(
                                packet = PPoGPacket.Data(
                                    sequence = outboundSequence.getThenIncrement(),
                                    data = chunk.toByteArray()
                                ),
                                attemptCount = 0,
                            )
                        }
                        .forEach {
                            outboundDataQueue.addLast(it)
                        }
                }
                pPoGStream.inboundPPoGBytesChannel.onReceive { bytes ->
                    val packet = bytes.asPPoGPacket()
                    verboseLog { "received packet: $packet" }
                    when (packet) {
                        is PPoGPacket.Ack -> {
                            removeResendsUpTo(packet.sequence)
                            if (packet == lastReceivedAck) {
                                logger.w("Received duplicate ACK; resending inflight packets")
                                resendInflightPackets()
                            }
                            // TODO remove resends of this packet from send queue (+ also remove up-to-them, which OG code didn't do?)

                            // Remove from in-flight packets, up until (including) this packet
                            // TODO warn if we don't have that packet inflight?
                            while (true) {
                                val inflightPacket = inflightPackets.removeFirstOrNull() ?: break
                                if (inflightPacket.packet.sequence == packet.sequence) break
                            }
                            if (inflightPackets.isEmpty()) {
                                cancelTimeout()
                            }
                            lastReceivedAck = packet
                        }

                        is PPoGPacket.Data -> {
                            if (packet.sequence != inboundSequence.get()) {
                                logger.w("data out of sequence; resending last ack")
                                lastSentAck?.let { sendPacketImmediately(it, params.pPoGversion) }
                            } else {
                                pebbleProtocolStreams.inboundPPBytes.writeByteArray(packet.data)
                                pebbleProtocolStreams.inboundPPBytes.flush()
                                inboundSequence.increment()
                                // TODO coalesced ACKing
                                lastSentAck = PPoGPacket.Ack(sequence = packet.sequence)
                                    .also { sendPacketImmediately(it, params.pPoGversion) }
                            }
                        }

                        is PPoGPacket.ResetComplete -> throw IllegalStateException("We don't handle resetting PPoG - disconnect and reconnect")
                        is PPoGPacket.ResetRequest -> throw IllegalStateException("We don't handle resetting PPoG - disconnect and reconnect")
                    }
                }
            }

            // Drain send queue
            while (inflightPackets.size < params.txWindow && !outboundDataQueue.isEmpty()) {
                if (closed) return
                val packet = outboundDataQueue.removeFirst()
                sendPacketImmediately(packet.packet, params.pPoGversion)
                rescheduleTimeout()
                inflightPackets.add(packet)
            }
        }
    }

    private fun maxDataBytes() = mtu - DATA_HEADER_OVERHEAD_BYTES

    private suspend fun sendPacketImmediately(packet: PPoGPacket, version: PPoGVersion) {
        if (packet is PPoGPacket.Data || packet is PPoGPacket.Ack) {
            verboseLog { "sendPacketImmediately: $packet" }
        } else {
            logger.d { "sendPacketImmediately: $packet" }
        }
        if (!pPoGPacketSender.sendPacket(packet.serialize(version))) {
            logger.e("Couldn't send packet!")
            throw IllegalStateException("Couldn't send packet!")
        }
    }

    fun updateMtu(mtu: Int) {
        if (mtu < this.mtu) throw IllegalStateException("Can't reduce MTU")
        this.mtu = mtu
    }
}

private const val DATA_HEADER_OVERHEAD_BYTES = 1 + 3
private const val MAX_SEQUENCE = 32
private const val MAX_NUM_RETRIES = 2
private val RESET_REQUEST_TIMEOUT = 10.seconds

private data class PPoGConnectionParams(
    val rxWindow: Int,
    val txWindow: Int,
    val pPoGversion: PPoGVersion,
)

private data class PacketToSend(
    val packet: PPoGPacket.Data,
    val attemptCount: Int,
)

private class Sequence {
    private var sequence = 0

    fun getThenIncrement(): Int {
        val currentSequence = sequence
        increment()
        return currentSequence
    }

    fun get(): Int = sequence

    fun increment() {
        sequence = (sequence + 1) % MAX_SEQUENCE
    }
}
