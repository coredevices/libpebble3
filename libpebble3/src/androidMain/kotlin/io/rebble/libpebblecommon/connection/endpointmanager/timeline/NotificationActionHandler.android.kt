package io.rebble.libpebblecommon.connection.endpointmanager.timeline

import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Intent
import android.os.Build
import android.os.Bundle
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.NotificationApps
import io.rebble.libpebblecommon.database.entity.BaseAction
import io.rebble.libpebblecommon.database.entity.MuteState
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification.AndroidPebbleNotificationListenerConnection
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification.LibPebbleNotificationAction
import io.rebble.libpebblecommon.packets.blobdb.TimelineAttribute
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import io.rebble.libpebblecommon.services.blobdb.TimelineActionResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.uuid.Uuid

class AndroidNotificationActionHandler(
    private val notificationListenerConnection: AndroidPebbleNotificationListenerConnection,
    private val notificationApps: NotificationApps,
) : PlatformNotificationActionHandler {
    companion object {
        private val logger = Logger.withTag(PlatformNotificationActionHandler::class.simpleName!!)

        private fun errorResponse(): TimelineActionResult {
            return TimelineActionResult(
                success = false,
                icon = TimelineIcon.ResultFailed,
                title = "Failed"
            )
        }
    }

    private val platformActions = mutableMapOf<Uuid, Map<UByte, LibPebbleNotificationAction>>()

    //TODO: Set from e.g. notification service
    fun setActionHandlers(itemId: Uuid, actionHandlers: Map<UByte, LibPebbleNotificationAction>) {
        platformActions[itemId] = actionHandlers
    }

    private fun makeFillIntent(
        pebbleInvokeAttrs: List<TimelineItem.Attribute>,
        notificationAction: LibPebbleNotificationAction
    ): Intent? {
        val responseText = pebbleInvokeAttrs.firstOrNull {
            it.attributeId.get() == TimelineAttribute.Title.id
        }?.content?.get()?.asByteArray()?.decodeToString()
            ?.take(1024 - 1) ?: run {
            logger.e { "No response text found for action while handling: $notificationAction" }
            return null
        }
        val replyInput = notificationAction.remoteInput?.remoteInput ?: run {
            logger.e { "No reply input found for action while handling: $notificationAction" }
            return null
        }
        val fillIntent = Intent()
        RemoteInput.addResultsToIntent(
            arrayOf(replyInput),
            fillIntent,
            Bundle().apply {
                putString(replyInput.resultKey, responseText)
            }
        )
        return fillIntent
    }

    private suspend fun handleReply(
        pebbleInvokeAttrs: List<TimelineItem.Attribute>,
        notificationAction: LibPebbleNotificationAction
    ): TimelineActionResult {
        val fillIntent =
            makeFillIntent(pebbleInvokeAttrs, notificationAction) ?: return errorResponse()
        val resultCode =
            notificationAction.pendingIntent?.let { actionIntent(it, fillIntent) } ?: run {
                logger.e { "No pending intent found while handling: $notificationAction as Reply" }
                return errorResponse()
            }
        logger.d { "handleReply() actionIntent result code: $resultCode" }
        return TimelineActionResult(
            success = true,
            icon = TimelineIcon.ResultSent,
            title = "Replied"
        )
    }

    private suspend fun handleGeneric(notificationAction: LibPebbleNotificationAction): TimelineActionResult {
        val resultCode = notificationAction.pendingIntent?.let { actionIntent(it) } ?: run {
            logger.e { "No pending intent found while handling: $notificationAction as Generic" }
            return errorResponse()
        }
        logger.d { "handleGeneric() actionIntent result code: $resultCode" }
        return TimelineActionResult(
            success = true,
            icon = TimelineIcon.GenericConfirmation,
            title = "Complete"
        )
    }

    private suspend fun handleDismiss(itemId: Uuid): TimelineActionResult {
        notificationListenerConnection.dismissNotification(itemId)
        return TimelineActionResult(
            success = true,
            icon = TimelineIcon.ResultDismissed,
            title = "Dismissed"
        )
    }

    private fun handleMuteApp(action: LibPebbleNotificationAction): TimelineActionResult {
        notificationApps.updateNotificationAppMuteState(
            packageName = action.packageName,
            muteState = MuteState.Always,
        )
        return TimelineActionResult(
            success = true,
            icon = TimelineIcon.ResultMute,
            title = "Muted"
        )
    }

    private fun handleMuteChannel(action: LibPebbleNotificationAction): TimelineActionResult {
        val channelId = action.channelId ?: return errorResponse()
        notificationApps.updateNotificationChannelMuteState(
            packageName = action.packageName,
            channelId = channelId,
            muteState = MuteState.Always,
        )
        return TimelineActionResult(
            success = true,
            icon = TimelineIcon.ResultMute,
            title = "Muted"
        )
    }

    override suspend operator fun invoke(
        itemId: Uuid,
        action: BaseAction,
        attributes: List<TimelineItem.Attribute>
    ): TimelineActionResult {
        val actionId = action.actionID
        val notificationAction =
            notificationListenerConnection.getNotificationAction(itemId, actionId)
                ?: run {
                    logger.e { "No notification found for action ID $actionId while handling: $itemId" }
                    return errorResponse()
                }
        logger.d { "Handling notification action on itemId $itemId: ${notificationAction.type}" }
        return when (notificationAction.type) {
            LibPebbleNotificationAction.ActionType.Reply -> handleReply(
                attributes,
                notificationAction
            )

            LibPebbleNotificationAction.ActionType.Dismiss -> handleDismiss(itemId)
            LibPebbleNotificationAction.ActionType.MuteApp -> handleMuteApp(notificationAction)
            LibPebbleNotificationAction.ActionType.MuteChannel -> handleMuteChannel(
                notificationAction
            )

            else -> handleGeneric(notificationAction)
        }
    }

    private suspend fun actionIntent(intent: PendingIntent, fillIntent: Intent? = null): Int? {
        val actionContext =
            notificationListenerConnection.getService()
        if (actionContext == null) {
            logger.w { "No service context to perform action" }
            return null
        }
        return suspendCancellableCoroutine { continuation ->
            val callback =
                PendingIntent.OnFinished { pendingIntent, intent, resultCode, resultData, resultExtras ->
                    continuation.resume(resultCode)
                }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val activityOptions = ActivityOptions.makeBasic().apply {
                    setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                }
                intent.send(
                    actionContext,
                    0,
                    fillIntent,
                    callback,
                    null,
                    null,
                    activityOptions.toBundle()
                )
            } else {
                intent.send(actionContext, 0, fillIntent, callback, null)
            }
        }
    }
}