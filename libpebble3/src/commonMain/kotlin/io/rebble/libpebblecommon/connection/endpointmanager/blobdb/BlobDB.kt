package io.rebble.libpebblecommon.connection.endpointmanager.blobdb

import co.touchlab.kermit.Logger
import coredev.BlobDatabase
import io.rebble.libpebblecommon.NotificationConfigFlow
import io.rebble.libpebblecommon.connection.Transport
import io.rebble.libpebblecommon.database.dao.BlobDbDao
import io.rebble.libpebblecommon.database.dao.BlobDbRecord
import io.rebble.libpebblecommon.database.dao.LockerEntryRealDao
import io.rebble.libpebblecommon.database.dao.NotificationAppRealDao
import io.rebble.libpebblecommon.database.dao.TimelineNotificationRealDao
import io.rebble.libpebblecommon.database.dao.TimelinePinRealDao
import io.rebble.libpebblecommon.database.dao.TimelineReminderRealDao
import io.rebble.libpebblecommon.database.entity.WatchSettingsDao
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.di.PlatformConfig
import io.rebble.libpebblecommon.metadata.WatchType
import io.rebble.libpebblecommon.packets.ProtocolCapsFlag
import io.rebble.libpebblecommon.packets.blobdb.BlobCommand
import io.rebble.libpebblecommon.packets.blobdb.BlobDB2Response
import io.rebble.libpebblecommon.packets.blobdb.BlobResponse
import io.rebble.libpebblecommon.services.blobdb.BlobDBService
import io.rebble.libpebblecommon.services.blobdb.WriteType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.random.Random
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

data class BlobDbDaos(
    private val lockerEntryDao: LockerEntryRealDao,
    private val notificationsDao: TimelineNotificationRealDao,
    private val timelinePinDao: TimelinePinRealDao,
    private val timelineReminderDao: TimelineReminderRealDao,
    private val notificationAppRealDao: NotificationAppRealDao,
    private val watchSettingsDao: WatchSettingsDao,
    private val platformConfig: PlatformConfig,
) {
    fun get(): Set<BlobDbDao<BlobDbRecord>> = buildSet {
        add(lockerEntryDao)
        add(notificationsDao)
        add(timelinePinDao)
        add(timelineReminderDao)
        add(watchSettingsDao)
        if (platformConfig.syncNotificationApps) {
            add(notificationAppRealDao)
        }
        // because typing
    } as Set<BlobDbDao<BlobDbRecord>>
}

interface TimeProvider {
    fun now(): Instant
}

class RealTimeProvider : TimeProvider {
    override fun now(): Instant = Clock.System.now()
}


class BlobDB(
    private val watchScope: ConnectionCoroutineScope,
    private val blobDBService: BlobDBService,
    private val transport: Transport,
    private val blobDatabases: BlobDbDaos,
    private val timeProvider: TimeProvider,
    private val notificationConfigFlow: NotificationConfigFlow,
) {
    protected val watchIdentifier: String = transport.identifier.asString

    companion object {
        private val BLOBDB_RESPONSE_TIMEOUT = 5.seconds
        private val QUERY_REFRESH_PERIOD = 1.hours
    }

    private val logger = Logger.withTag("BlobDB-$watchIdentifier")
    private val random = Random

    /**
     * Run [query] continually, updating the query timestamp (so that it does not become stale).
     */
    private fun dynamicQuery(
        dao: BlobDbDao<BlobDbRecord>,
        insert: Boolean,
        collector: suspend (items: List<BlobDbRecord>) -> Unit,
    ) {
        val initialTimestamp = timeProvider.now()
        watchScope.launch {
            val tickerFlow = flow {
                while (true) {
                    emit(Unit)
                    delay(QUERY_REFRESH_PERIOD)
                }
            }
            tickerFlow
                .flatMapLatest {
                    if (insert) {
                        dao.dirtyRecordsForWatchInsert(
                            transport = transport.identifier.asString,
                            timestampMs = timeProvider.now().toEpochMilliseconds(),
                            insertOnlyAfterMs = initialTimestamp.toEpochMilliseconds(),
                        )
                    } else {
                        dao.dirtyRecordsForWatchDelete(
                            transport = transport.identifier.asString,
                            timestampMs = timeProvider.now().toEpochMilliseconds(),
                        )
                    }
                }
                .conflate()
                .distinctUntilChanged()
                .collect { items ->
                    collector(items)
                    // debounce
                    delay(1.seconds)
                }
        }
    }

    fun init(
        watchType: WatchType,
        unfaithful: Boolean,
        previouslyConnected: Boolean,
        capabilities: Set<ProtocolCapsFlag>,
    ) {
        watchScope.launch {
            if (unfaithful || !previouslyConnected) {
                logger.d("unfaithful: wiping DBs on watch")
                // Clear all DBs on watch (whether we have local DBs for them or not)
                BlobDatabase.entries.forEach { db ->
                    sendWithTimeout(
                        BlobCommand.ClearCommand(
                            token = generateToken(),
                            database = db,
                        )
                    )
                }
                // Mark all of our local DBs not synched
                blobDatabases.get().forEach { db ->
                    db.markAllDeletedFromWatch(transport.identifier.asString)
                }
            }

            blobDatabases.get().forEach { db ->
                db.deleteStaleRecords(timeProvider.now().toEpochMilliseconds())
                dynamicQuery(dao = db, insert = true) { dirty ->
                    dirty.forEach { item ->
                        handleInsert(db, item, watchType, capabilities)
                    }
                }
                dynamicQuery(dao = db, insert = false) { dirty ->
                    dirty.forEach { item ->
                        handleDelete(db, item)
                    }
                }
            }

            blobDBService.writes.collect { message ->
                val dao = blobDatabases.get().find { it.databaseId() == message.database }
                val result = dao?.handleWrite(
                    write = message,
                    transport = transport.identifier.asString,
                ) ?: BlobResponse.BlobStatus.Success
                val response = when (message.writeType) {
                    WriteType.Write -> BlobDB2Response.WriteResponse(message.token, result)
                    WriteType.WriteBack -> BlobDB2Response.WriteBackResponse(message.token, result)
                }
                blobDBService.sendResponse(response)
            }
        }
    }

    private suspend fun handleInsert(
        db: BlobDbDao<BlobDbRecord>,
        item: BlobDbRecord,
        watchType: WatchType,
        capabilities: Set<ProtocolCapsFlag>,
    ) {
        val value = item.record.value(watchType, capabilities)
        if (value == null) {
            return
        }
        if (notificationConfigFlow.value.obfuscateContent) {
            logger.d("insert: ${item.record.key()} hashcode: ${item.recordHashcode}")
        } else {
            logger.d("insert: $item")
        }
        val result = sendWithTimeout(
            BlobCommand.InsertCommand(
                token = generateToken(),
                database = db.databaseId(),
                key = item.record.key(),
                value = value,
            )
        )
        logger.d("insert: result = ${result?.responseValue}")
        if (result?.responseValue == BlobResponse.BlobStatus.Success) {
            db.markSyncedToWatch(
                transport = transport.identifier.asString,
                item = item,
                hashcode = item.recordHashcode,
            )
        }
    }

    private suspend fun handleDelete(
        db: BlobDbDao<BlobDbRecord>,
        item: BlobDbRecord,
    ) {
        if (notificationConfigFlow.value.obfuscateContent) {
            logger.d("delete: ${item.record.key()} hashcode: ${item.recordHashcode}")
        } else {
            logger.d("delete: $item")
        }
        val result = sendWithTimeout(
            BlobCommand.DeleteCommand(
                token = generateToken(),
                database = db.databaseId(),
                key = item.record.key(),
            )
        )
        logger.d("delete: result = ${result?.responseValue}")
        if (result?.responseValue == BlobResponse.BlobStatus.Success) {
            db.markDeletedFromWatch(
                transport = transport.identifier.asString,
                item = item,
                hashcode = item.recordHashcode,
            )
        }
    }

    private fun generateToken(): UShort {
        return random.nextInt(0, UShort.MAX_VALUE.toInt()).toUShort()
    }

    private suspend fun sendWithTimeout(command: BlobCommand): BlobResponse? =
        withTimeoutOrNull(BLOBDB_RESPONSE_TIMEOUT) {
            blobDBService.send(command)
        }
}
