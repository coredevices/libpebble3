package io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification

import android.app.Notification.WearableExtender
import android.service.notification.StatusBarNotification
import io.rebble.libpebblecommon.SystemAppIDs.NOTIFICATIONS_APP_UUID
import io.rebble.libpebblecommon.database.entity.ChannelItem
import io.rebble.libpebblecommon.database.entity.NotificationAppItem
import io.rebble.libpebblecommon.database.entity.TimelineNotification
import io.rebble.libpebblecommon.database.entity.buildTimelineNotification
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import kotlinx.datetime.Instant
import kotlin.uuid.Uuid

data class LibPebbleNotification(
    val packageName: String,
    val uuid: Uuid,
    val groupKey: String?,
    val key: String,
    val timestamp: Instant,
    val title: String?,
    val body: String?,
    val icon: TimelineIcon,
    val actions: List<LibPebbleNotificationAction>
) {
    fun displayDataEquals(other: LibPebbleNotification): Boolean {
        return packageName == other.packageName &&
                title == other.title &&
                body == other.body
    }

    companion object {
        fun actionsFromStatusBarNotification(
            sbn: StatusBarNotification,
            app: NotificationAppItem,
            channel: ChannelItem?,
        ): List<LibPebbleNotificationAction> {
            val dismissAction = LibPebbleNotificationAction.dismissActionFromNotification(
                packageName = sbn.packageName,
                notification = sbn.notification
            )
            val contentAction = LibPebbleNotificationAction.contentActionFromNotification(
                packageName = sbn.packageName,
                notification = sbn.notification
            )
            val muteAction = LibPebbleNotificationAction.muteActionFrom(app)
            val muteChannelAction = LibPebbleNotificationAction.muteChannelActionFrom(
                app = app,
                channel = channel,
            )
            val wearableActions = WearableExtender(sbn.notification).actions
            val actionsToUse = when {
                wearableActions != null && wearableActions.isNotEmpty() -> wearableActions
                else -> sbn.notification.actions?.asList() ?: emptyList()
            }
            val actions = actionsToUse.mapNotNull {
                LibPebbleNotificationAction.fromNotificationAction(
                    packageName = sbn.packageName,
                    action = it
                )
            }
            return buildList {
                dismissAction?.let { add(it) }
                muteAction?.let { add(it) }
                muteChannelAction?.let { add(it) }
                contentAction?.let { add(it) }
                addAll(actions)
            }
        }
    }

    fun toTimelineNotification(): TimelineNotification = buildTimelineNotification(
        timestamp = timestamp,
        parentId = NOTIFICATIONS_APP_UUID,
    ) {
        itemID = uuid

        layout = TimelineItem.Layout.GenericNotification
        attributes {
            title?.let {
                title { it }
            }
            body?.let {
                body { it }
            }
            tinyIcon { icon }
        }
        actions {
            actions.forEach { action ->
                action(action.type.toProtocolType()) {
                    attributes {
                        title { action.title }
                        action.remoteInput?.suggestedResponses?.let {
                            cannedResponse { it.take(8) }
                        }
                    }
                }
            }
        }
    }
}