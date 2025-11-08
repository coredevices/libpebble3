package io.rebble.libpebblecommon.connection.endpointmanager.timeline

import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.database.dao.TimelineNotificationRealDao
import io.rebble.libpebblecommon.di.ConnectionCoroutineScope
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import io.rebble.libpebblecommon.services.blobdb.TimelineActionResult
import io.rebble.libpebblecommon.services.blobdb.TimelineService
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.coroutines.cancellation.CancellationException
import kotlin.uuid.Uuid

class ActionOverrides {
    val actionHandlerOverrides =
        mutableMapOf<Uuid, Map<UByte, CustomTimelineActionHandler>>()

    fun setActionHandlers(itemId: Uuid, actionHandlers: Map<UByte, CustomTimelineActionHandler>) {
        actionHandlerOverrides[itemId] = actionHandlers
    }
}

class TimelineActionManager(
    private val timelineService: TimelineService,
    private val notifActionHandler: PlatformNotificationActionHandler,
    private val scope: ConnectionCoroutineScope,
    private val notificationDao: TimelineNotificationRealDao,
    private val actionOverrides: ActionOverrides,
) {
    companion object {
        private val logger = Logger.withTag(TimelineActionManager::class.simpleName!!)
    }

    fun init() {
        timelineService.actionInvocations.onEach {
            handleAction(it)
        }.launchIn(scope)
    }

    private suspend fun handleTimelineAction(
        itemId: Uuid,
        action: TimelineItem.Action,
        attributes: List<TimelineItem.Attribute>
    ): TimelineActionResult {
        TODO()
    }

    private suspend fun handleAction(
        invocation: TimelineService.TimelineActionInvocation
    ) {
        val itemId = invocation.itemId
        val actionId = invocation.actionId
        val attributes = invocation.attributes
        val item = notificationDao.getEntry(itemId) ?: run {
            logger.w {
                "Received action for item $itemId, but it doesn't exist in the database"
            }
            return
        }
        val action = item.content.actions.firstOrNull { it.actionID == actionId } ?: run {
            logger.w {
                "Received action for item $itemId, but it doesn't exist in the pin (action ID $actionId not in ${item.content.actions.map { it.actionID }})"
            }
            return
        }
        val result = try {
            actionOverrides.actionHandlerOverrides[itemId]?.get(actionId)?.let {
                it(attributes)
            } ?:
            //when (item.) {
//                BlobCommand.BlobDatabase.Pin -> {
//                    handleTimelineAction(itemId, action, attributes)
//                }
//                BlobCommand.BlobDatabase.Notification -> {
                notifActionHandler(itemId, action, attributes)
//                }
//                else -> error(
//                    "Received action for item $itemId, but it is not a notification or pin (${item.watchDatabase})"
//                )
//            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.e(e) {
                "Error handling action for item $itemId: ${e.message}"
            }
            TimelineActionResult(
                success = false,
                icon = TimelineIcon.ResultFailed,
                title = "Failed"
            )
        }
        invocation.respond(result)
    }
}

typealias CustomTimelineActionHandler = (List<TimelineItem.Attribute>) -> TimelineActionResult
