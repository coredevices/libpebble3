package io.rebble.libpebblecommon.locker

import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.readRemaining
import io.rebble.libpebblecommon.ErrorTracker
import io.rebble.libpebblecommon.WatchConfigFlow
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LockerApi
import io.rebble.libpebblecommon.connection.PebbleIdentifier
import io.rebble.libpebblecommon.connection.UserFacingError
import io.rebble.libpebblecommon.connection.WatchManager
import io.rebble.libpebblecommon.connection.WebServices
import io.rebble.libpebblecommon.connection.endpointmanager.blobdb.TimeProvider
import io.rebble.libpebblecommon.database.Database
import io.rebble.libpebblecommon.database.entity.CompanionApp
import io.rebble.libpebblecommon.database.entity.LockerEntry
import io.rebble.libpebblecommon.database.entity.LockerEntryAppstoreData
import io.rebble.libpebblecommon.database.entity.LockerEntryPlatform
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.disk.pbw.PbwApp
import io.rebble.libpebblecommon.disk.pbw.toLockerEntry
import io.rebble.libpebblecommon.metadata.WatchType
import io.rebble.libpebblecommon.web.LockerModel
import io.rebble.libpebblecommon.web.WebSyncManager
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout
import kotlinx.io.IOException
import kotlinx.io.Source
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.Uuid

class WebSyncManagerProvider(val webSyncManager: () -> WebSyncManager)

class Locker(
    private val watchManager: WatchManager,
    database: Database,
    private val lockerPBWCache: LockerPBWCache,
    private val config: WatchConfigFlow,
    private val libPebbleCoroutineScope: LibPebbleCoroutineScope,
    private val clock: Clock,
    private val webServices: WebServices,
    private val webSyncManagerProvider: WebSyncManagerProvider,
    private val timeProvider: TimeProvider,
    private val errorTracker: ErrorTracker,
) : LockerApi {
    private val lockerEntryDao = database.lockerEntryDao()

    companion object {
        private val logger = Logger.withTag("Locker")
    }

    override suspend fun sideloadApp(pbwPath: Path): Boolean =
        try {
            sideloadApp(pbwApp = PbwApp(pbwPath), loadOnWatch = true)
        } catch (e: Exception) {
            Logger.e(e) { "Error while sideloading app" }
            errorTracker.reportError(UserFacingError.FailedToSideloadApp("Error while sideloading app"))
            false
        }

    /**
     * Get the locker contents, filtered by type/search query. Limit this, so that you don't run
     * out of memory if there is a particularly large locker.
     */
    override fun getLocker(
        type: AppType,
        searchQuery: String?,
        limit: Int
    ): Flow<List<LockerWrapper>> =
        lockerEntryDao.getAllFlow(type.code, searchQuery, limit)
            .map { entries ->
                SystemApps.entries.filter { it.type == type }
                    .map { systemApp ->
                        systemApp.wrap(0)
                    } + entries.mapNotNull { app ->
                    app.wrap(config)
                }
            }

    override fun getAllLockerBasicInfo(): Flow<List<AppBasicProperties>> {
        return lockerEntryDao.getAllBasicInfoFlow().map {
            it.mapNotNull { entry ->
                val appType = AppType.fromString(entry.type) ?: return@mapNotNull null
                AppBasicProperties(
                    id = entry.id,
                    title = entry.title,
                    type = appType,
                    developerName = entry.developerName,
                )
            }
        }
    }

    override fun getLockerApp(id: Uuid): Flow<LockerWrapper?> {
        val asSystemApp = findSystemApp(id)
        if (asSystemApp != null) {
            return flow { emit(asSystemApp.wrap(0)) }
        }
        return lockerEntryDao.getEntryFlow(id).map { it?.wrap(config) }
    }

    override suspend fun setAppOrder(id: Uuid, order: Int) {
        libPebbleCoroutineScope.async {
            lockerEntryDao.setOrder(id, order, config.value.lockerSyncLimit)
        }.await()
    }

    override suspend fun waitUntilAppSyncedToWatch(id: Uuid, identifier: PebbleIdentifier, timeout: Duration): Boolean {
        logger.v { "waitUntilAppSyncedToWatch $id" }
        try {
            withTimeout(timeout) {
                lockerEntryDao.dirtyRecordsForWatchInsert(
                    identifier = identifier.asString,
                    timestampMs = timeProvider.now().toEpochMilliseconds(),
                    insertOnlyAfterMs = timeProvider.now().toEpochMilliseconds(),
                ).filter { entries ->
                    // Wait until there is no match for this app in the dirty records
                    entries.find { entry ->
                        entry.record.id == id
                    } == null
                }.first()
            }
            logger.v { "waitUntilAppSyncedToWatch $id done!" }
            return true
        } catch (_: TimeoutCancellationException) {
            logger.w { "waitUntilAppSyncedToWatch: timed out" }
            return false
        }
    }

    override suspend fun removeApp(id: Uuid): Boolean {
        val lockerEntry = lockerEntryDao.getEntry(id)
        if (lockerEntry == null) {
            logger.e { "removeApp: not found: $id" }
            return false
        }
        logger.d { "Deleting app: $lockerEntry" }
        lockerPBWCache.deleteApp(id)
        if (!lockerEntry.sideloaded) {
            // Need to remove from remote locker (and only process deletion if that succeeds)
            if (!webServices.removeFromLocker(id)) {
                logger.w { "Failed to remove from remote locker" }
                errorTracker.reportError(UserFacingError.FailedToRemovePbwFromLocker("Failed to remove app from remote locker"))
                return false
            }
        }
        lockerEntryDao.markForDeletion(id)
        if (lockerEntry.sideloaded) {
            logger.d { "Requesting locker sync after removing sideloaded app" }
            // If it was sideloaded, trigger a resync (in case the same app is in the locker).
            webSyncManagerProvider.webSyncManager().requestLockerSync()
        }
        return true
    }

    suspend fun getApp(uuid: Uuid): LockerEntry? = lockerEntryDao.getEntry(uuid)

    suspend fun update(locker: LockerModel) {
        logger.d("update: ${locker.applications.size}")
        val existingApps = lockerEntryDao.getAll().associateBy { it.id }.toMutableMap()
        val toInsert = locker.applications.mapNotNull { new ->
            val newEntity = new.asEntity()
            val existing = existingApps.remove(newEntity.id)
            if (existing == null) {
                new.asEntity()
            } else {
                val newWithExistingOrder = newEntity.copy(orderIndex = existing.orderIndex)
                if (newWithExistingOrder != existing && !existing.sideloaded) {
                    newWithExistingOrder
                } else {
                    null
                }
            }
        }
        logger.d { "inserting: ${toInsert.map { "${it.id} / ${it.title}" }}" }
        lockerEntryDao.insertOrReplaceAndOrder(toInsert, config.value.lockerSyncLimit)
        val toDelete = existingApps.mapNotNull { if (!it.value.sideloaded) it.key else null }
        logger.d { "deleting: $toDelete" }
        lockerEntryDao.markAllForDeletion(toDelete)
    }

    /**
     * Sideload an app to the watch.
     * This will insert the app into the locker database and optionally install it/launch it on the watch.
     * @param pbwApp The app to sideload.
     * @param loadOnWatch Whether to fully install the app on the watch (launch it). Defaults to true.
     */
    suspend fun sideloadApp(pbwApp: PbwApp, loadOnWatch: Boolean): Boolean {
        logger.d { "Sideloading app ${pbwApp.info.longName}" }
        val lockerEntry = pbwApp.toLockerEntry(clock.now())
        pbwApp.source().buffered().use {
            lockerPBWCache.addPBWFileForApp(lockerEntry.id, pbwApp.info.versionLabel, it)
        }
        val tasks = if (loadOnWatch) {
            watchManager.watches.value.filterIsInstance<ConnectedPebbleDevice>().map {
                libPebbleCoroutineScope.async {
                    val entryStatus = lockerEntryDao.existsOnWatch(
                        it.identifier.asString,
                        lockerEntry.id
                    ).drop(1).first()
                    if (entryStatus) {
                        logger.d { "App synced, launching" }
                        it.launchApp(lockerEntry.id)
                    }
                }
            }
        } else {
            null
        }
        lockerEntryDao.insertOrReplaceAndOrder(lockerEntry, config.value.lockerSyncLimit)
        return try {
            withTimeout(40.seconds) {
                tasks?.awaitAll()
                true
            }
        } catch (e: TimeoutCancellationException) {
            logger.w { "Timeout while waiting for app to sync+launch on watches" }
            false
        }
    }
}

fun SystemApps.wrap(order: Int): LockerWrapper.SystemApp = LockerWrapper.SystemApp(
    properties = AppProperties(
        id = uuid,
        type = type,
        title = displayName,
        developerName = "Pebble",
        platforms = compatiblePlatforms.map {
            AppPlatform(
                watchType = it,
                screenshotImageUrl = null,
                listImageUrl = null,
                iconImageUrl = null,
            )
        },
        version = null,
        hearts = null,
        category = null,
        iosCompanion = null,
        androidCompanion = null,
        order = order,
    ),
    systemApp = this,
)

fun LockerEntry.wrap(config: WatchConfigFlow): LockerWrapper.NormalApp? {
    val type = AppType.fromString(type) ?: return null
    return LockerWrapper.NormalApp(
        properties = AppProperties(
            id = id,
            type = type,
            title = title,
            developerName = developerName,
            platforms = platforms.mapNotNull platforms@{
                val platform = WatchType.fromCodename(it.name) ?: return@platforms null
                AppPlatform(
                    watchType = platform,
                    screenshotImageUrl = it.screenshotImageUrl,
                    listImageUrl = it.listImageUrl,
                    iconImageUrl = it.iconImageUrl,
                    description = it.description,
                )
            },
            version = version,
            hearts = appstoreData?.hearts,
            category = category,
            iosCompanion = iosCompanion,
            androidCompanion = androidCompanion,
            order = orderIndex,
        ),
        sideloaded = sideloaded,
        configurable = configurable,
        sync = orderIndex < config.value.lockerSyncLimit,
    )
}

fun findSystemApp(uuid: Uuid): SystemApps? = SystemApps.entries.find { it.uuid == uuid }

fun io.rebble.libpebblecommon.web.LockerEntry.asEntity(): LockerEntry {
    val uuid = Uuid.parse(uuid)
    return LockerEntry(
        id = uuid,
        version = version ?: "", // FIXME
        title = title,
        type = type,
        developerName = developer.name,
        configurable = isConfigurable,
        pbwVersionCode = pbw?.releaseId ?: "", // FIXME
        category = category,
        sideloaded = false,
        appstoreData = LockerEntryAppstoreData(
            hearts = hearts,
            developerId = developer.id,
            timelineEnabled = isTimelineEnabled ?: false,
            removeLink = links.remove,
            shareLink = links.share,
            pbwLink = pbw?.file ?: "", // FIXME
            userToken = userToken,
        ),
        platforms = hardwarePlatforms.map { platform ->
            LockerEntryPlatform(
                lockerEntryId = uuid,
                sdkVersion = platform.sdkVersion,
                processInfoFlags = platform.pebbleProcessInfoFlags,
                name = platform.name,
                screenshotImageUrl = platform.images.screenshot,
                listImageUrl = platform.images.list,
                iconImageUrl = platform.images.icon,
                pbwIconResourceId = pbw?.iconResourceId ?: 0,
                description = platform.description,
            )
        },
        iosCompanion = companions.ios?.let {
            CompanionApp(
                id = it.id,
                icon = it.icon,
                name = it.name,
                url = it.url,
                required = it.required,
                pebblekitVersion = it.pebblekitVersion,
            )
        },
        androidCompanion = companions.android?.let {
            CompanionApp(
                id = it.id,
                icon = it.icon,
                name = it.name,
                url = it.url,
                required = it.required,
                pebblekitVersion = it.pebblekitVersion,
            )
        },
        orderIndex = -1,
    )
}

expect fun getLockerPBWCacheDirectory(context: AppContext): Path

class StaticLockerPBWCache(
    context: AppContext,
    private val httpClient: HttpClient,
    private val errorTracker: ErrorTracker,
) : LockerPBWCache(context) {
    private val logger = Logger.withTag("StaticLockerPBWCache")

    override suspend fun handleCacheMiss(appId: Uuid, version: String, locker: Locker): Path? {
        logger.d { "handleCacheMiss: $appId" }
        val pbwPath = pathForApp(appId, version)
        val pbwUrl = locker.getApp(appId)?.appstoreData?.pbwLink ?: return null
        return try {
            withTimeout(20.seconds) {
                logger.d { "get: $pbwUrl" }
                val response = try {
                    httpClient.get(pbwUrl)
                } catch (e: IOException) {
                    logger.w(e) { "Error fetching pbw: ${e.message}" }
                    errorTracker.reportError(UserFacingError.FailedToDownloadPbw("Error fetching pbw: ${e.message}"))
                    return@withTimeout null
                }
                if (!response.status.isSuccess()) {
                    logger.i("http call failed: $response")
                    errorTracker.reportError(UserFacingError.FailedToDownloadPbw("Error fetching pbw: ${response.status.description}"))
                    return@withTimeout null
                }
                SystemFileSystem.sink(pbwPath).use { sink ->
                    response.bodyAsChannel().readRemaining().transferTo(sink)
                }
                logger.d { "Successfully fetched pbw to: $pbwPath" }
                pbwPath
            }
        } catch (_: TimeoutCancellationException) {
            logger.w { "Timeout fetching pbw" }
            null
        }
    }
}

abstract class LockerPBWCache(context: AppContext) {
    private val cacheDir = getLockerPBWCacheDirectory(context)
    private val pkjsCacheDir = Path(getLockerPBWCacheDirectory(context), "pkjs")

    protected fun pathForApp(appId: Uuid, version: String): Path {
        return Path(cacheDir, "${appId}_${version}.pbw")
    }

    protected fun pkjsPathForApp(appId: Uuid): Path {
        return Path(pkjsCacheDir, "$appId.js")
    }

    protected abstract suspend fun handleCacheMiss(appId: Uuid, version: String, locker: Locker): Path?

    suspend fun getPBWFileForApp(appId: Uuid, version: String, locker: Locker): Path {
        val pbwPath = pathForApp(appId, version)
        return if (SystemFileSystem.exists(pbwPath)) {
            pbwPath
        } else {
            // Delete any other cached versions for this app
            deleteApp(appId)
            handleCacheMiss(appId, version, locker) ?: error("Failed to find PBW file for app $appId")
        }
    }

    fun addPBWFileForApp(appId: Uuid, version: String, source: Source) {
        val targetPath = pathForApp(appId, version)
        SystemFileSystem.sink(targetPath).use { sink ->
            source.transferTo(sink)
        }
    }

    private fun sanitizeJS(js: String): String {
        // Replace non-breaking spaces with regular spaces
        return js.replace("\u00a0", " ")
    }

    fun getPKJSFileForApp(appId: Uuid, version: String): Path {
        val pkjsPath = pkjsPathForApp(appId)
        val appPath = pathForApp(appId, version)
        return when {
            SystemFileSystem.exists(pkjsPath) -> pkjsPath
            SystemFileSystem.exists(appPath) -> {
                SystemFileSystem.createDirectories(pkjsCacheDir, false)
                val pbwApp = PbwApp(pathForApp(appId, version))
                pbwApp.getPKJSFile().use { source ->
                    val js = sanitizeJS(source.readString())
                    SystemFileSystem.sink(pkjsPath).buffered().use { sink ->
                        sink.writeString(js)
                    }
                }
                pkjsPath
            }

            else -> error("Failed to find PBW file for app $appId while extracting JS")
        }
    }

    fun clearPKJSFileForApp(appId: Uuid) {
        val pkjsPath = pkjsPathForApp(appId)
        if (SystemFileSystem.exists(pkjsPath)) {
            SystemFileSystem.delete(pkjsPath)
        }
    }

    fun deleteApp(appId: Uuid) {
        clearPKJSFileForApp(appId)
        if (SystemFileSystem.exists(cacheDir)) {
            SystemFileSystem.list(cacheDir).forEach {
                // Delete all pbws for this uuid
                if (it.name.startsWith(appId.toString())) {
                    SystemFileSystem.delete(it)
                }
            }
        }
    }
}
