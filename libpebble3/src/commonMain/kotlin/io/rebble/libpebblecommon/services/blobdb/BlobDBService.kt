package io.rebble.libpebblecommon.services.blobdb

import co.touchlab.kermit.Logger
import coredev.BlobDatabase
import io.rebble.libpebblecommon.PacketPriority
import io.rebble.libpebblecommon.connection.PebbleProtocolHandler
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.packets.blobdb.BlobCommand
import io.rebble.libpebblecommon.packets.blobdb.BlobDB2Command
import io.rebble.libpebblecommon.packets.blobdb.BlobDB2Response
import io.rebble.libpebblecommon.packets.blobdb.BlobResponse
import io.rebble.libpebblecommon.services.ProtocolService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch

/**
 * Singleton to handle sending BlobDB commands cleanly, by allowing registered callbacks to be triggered when the sending packet receives a BlobResponse
 * @see BlobResponse
 */
class BlobDBService(
    private val protocolHandler: PebbleProtocolHandler,
    private val scope: ConnectionCoroutineScope,
) : ProtocolService {
    private val pending: MutableMap<UShort, CompletableDeferred<BlobResponse>> = mutableMapOf()
    private val logger = Logger.withTag("BlobDBService")
    // Add a replay cache so that BlobDb can catch up with the post-connect WriteBack sync when it
    // is initialized (it will actually only be once message - it waits for that to be ACKed).
    private val _writes = MutableSharedFlow<DbWrite>(replay = 20)
    val writes = _writes.asSharedFlow()
    private val _syncCompletes = MutableSharedFlow<BlobDatabase>(replay = 20)
    val syncCompletes = _syncCompletes.asSharedFlow()

    fun init() {
        scope.launch {
            protocolHandler.inboundMessages.collect { packet ->
                when (packet) {
                    is BlobResponse -> {
                        pending.remove(packet.token.get())?.complete(packet)
                    }

                    is BlobDB2Command.Write -> {
                        logger.d("Write: db=${packet.database} key=${packet.key} value=${packet.value}")
                        _writes.emit(
                            DbWrite(
                                token = packet.token.get(),
                                database = BlobDatabase.from(packet.database.get()),
                                timestamp = packet.timestamp.get(),
                                key = packet.key.get(),
                                value = packet.value.get(),
                                WriteType.Write,
                            )
                        )
                    }

                    is BlobDB2Command.WriteBack -> {
                        logger.d("WriteBack: db=${packet.database} key=${packet.key} value=${packet.value}")
                        _writes.emit(
                            DbWrite(
                                token = packet.token.get(),
                                database = BlobDatabase.from(packet.database.get()),
                                timestamp = packet.timestamp.get(),
                                key = packet.key.get(),
                                value = packet.value.get(),
                                WriteType.WriteBack,
                            )
                        )
                    }

                    is BlobDB2Command.SyncDone -> {
                        logger.d("SyncDone: token=${packet.token}")
                        _syncCompletes.emit(BlobDatabase.from(packet.database.get()))
                    }
                }
            }
        }
    }

    suspend fun syncDirtyDbs() {
        val resp = send(BlobDB2Command.DirtyDatabase())
        val dirty = resp as? BlobDB2Response.DirtyDatabaseResponse
        logger.d("DirtyDatabase: ${dirty?.databaseIds}")
        if (dirty == null) return
        dirty.databaseIds.list.forEach {
            val db = BlobDatabase.from(it.get())
            if (db == BlobDatabase.Invalid) return@forEach
            startSync(db)
        }
//            clearDb(BlobCommand.BlobDatabase.CannedResponses)
    }

//    private suspend fun insertApp(packageName: String, name: String) {
//        val token = 0
//        val item = NotificationAppItem(
//            attributes = listOf(
//                TimelineItem.Attribute(
//                    attributeId = TimelineAttribute.AppName.id,
//                    content = name.encodeToByteArray().toUByteArray(),
//                ),
//                TimelineItem.Attribute(
//                    attributeId = TimelineAttribute.MuteDayOfWeek.id,
//                    content = ubyteArrayOf(0u),
//                ),
//                TimelineItem.Attribute(
//                    attributeId = TimelineAttribute.LastUpdated.id,
//                    content = Clock.System.now().epochSeconds.toUInt().let {
//                        SUInt(StructMapper(), it, endianness = Endian.Big).toBytes()
//                    },
//                ),
//            )
//        )
//        val res = send(
//            BlobCommand.InsertCommand(
//                token = token.toUShort(),
//                database = BlobCommand.BlobDatabase.CannedResponses,
//                key = packageName.encodeToByteArray().toUByteArray(),
//                value = item.toBytes(),
//            )
//        )
//        logger.d("insert $name res=$res")
//    }

    suspend fun startSync(db: BlobDatabase) {
        logger.d("startSync for $db")
        val token = 0
        val tokenUShort = token.toUShort()
        val startRes = send(BlobDB2Command.StartSync(token = tokenUShort, database = db))
        logger.d("startRes = $startRes")
        val startSyncRes = startRes as? BlobDB2Response.StartSyncResponse
        logger.d("...StartSync res = ${startSyncRes?.status}")
        protocolHandler.inboundMessages.filterIsInstance<BlobDB2Command.SyncDone>()
            .filter { it.token.get() == tokenUShort }
            .first()
        val doneRes = sendResponse(BlobDB2Response.SyncDoneResponse(token = tokenUShort))
        logger.d("doneRes=$doneRes")
    }

    /**
     * Send a BlobCommand, with an optional callback to be triggered when a matching BlobResponse is received
     * @see BlobCommand
     * @see BlobResponse
     * @param packet the packet to send
     *
     * @return [BlobResponse] from the watch or *null* if the sending failed
     */
    suspend fun send(
        packet: BlobCommand,
        priority: PacketPriority = PacketPriority.NORMAL
    ): BlobResponse {
        val result = CompletableDeferred<BlobResponse>()
        pending[packet.token.get()] = result

        protocolHandler.send(packet, priority)

        return result.await()
    }

    suspend fun send(packet: BlobDB2Command): BlobDB2Response {
        logger.d("send BlobDB2Command $packet")
        return protocolHandler.inboundMessages.onSubscription {
            logger.d("send BlobDB2Command $packet ... onsubscription")
            protocolHandler.send(packet)
        }.filterIsInstance(BlobDB2Response::class)
            .filter { it.token == packet.token }
            .first()
    }

    suspend fun sendResponse(response: BlobDB2Response) {
        protocolHandler.send(response)
    }
}

data class DbWrite(
    val token: UShort,
    val database: BlobDatabase,
    val timestamp: UInt,
    val key: UByteArray,
    val value: UByteArray,
    val writeType: WriteType,
)

enum class WriteType {
    Write,
    WriteBack,
}