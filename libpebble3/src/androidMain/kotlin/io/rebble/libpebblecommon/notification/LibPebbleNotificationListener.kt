package io.rebble.libpebblecommon.notification

import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.os.UserHandle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.NotificationConfigFlow
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.Watches
import io.rebble.libpebblecommon.database.entity.ChannelGroup
import io.rebble.libpebblecommon.database.entity.ChannelItem
import io.rebble.libpebblecommon.database.entity.MuteState
import io.rebble.libpebblecommon.di.LibPebbleKoinComponent
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification.AndroidPebbleNotificationListenerConnection
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification.NotificationHandler
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.core.component.get
import kotlin.uuid.Uuid

class LibPebbleNotificationListener : NotificationListenerService(), LibPebbleKoinComponent {
    companion object {
        private val logger = Logger.withTag("LibPebbleNotificationListener")
        fun componentName(context: Context) = ComponentName(context, LibPebbleNotificationListener::class.java)
    }

    private var warnedAboutNotificationchannelsPermission = false

    private var notificationListenerScope = MainScope()

    override fun onCreate() {
        super.onCreate()
        logger.v { "onCreate: ($this)" }
    }

    private val notificationHandler: NotificationHandler = get()
    private val connection: AndroidPebbleNotificationListenerConnection = get()

    private val configHolder: NotificationConfigFlow = get()

    private val watches: Watches = get<LibPebble>()

    fun cancelNotification(itemId: Uuid) {
        val sbn = notificationHandler.getNotification(itemId) ?: return
        cancelNotification(sbn.key)
    }

    override fun onBind(intent: Intent?): IBinder? {
        logger.d { "onBind() ($this)" }
        notificationHandler.onServiceBound()
        return super.onBind(intent)
    }

    override fun onListenerConnected() {
        // Note: this can be called twice if Android gets confused when the app is killed/restarted
        // quickly (as it will be often, because Android agressively tries to keep the process
        // running because it has a notification listener...). Don't do anything here that shouldn't
        // be done twice.
        logger.d { "onListenerConnected() ($this)" }
        connection.setService(this)

        notificationListenerScope = MainScope()
        controlListenerHints()

//        try {
//            notificationHandler.setActiveNotifications(getActiveNotifications().toList())
//        } catch (e: SecurityException) {
//            logger.e("error getting active notifications", e)
//        }
    }

    override fun onListenerDisconnected() {
        logger.d { "onListenerDisconnected() ($this)" }
        connection.setService(null)
        notificationListenerScope.cancel()
    }

    override fun onNotificationChannelModified(
        pkg: String,
        user: UserHandle,
        channel: NotificationChannel,
        modificationType: Int,
    ) {
        notificationHandler.onChannelChanged()
    }

    override fun onNotificationChannelGroupModified(
        pkg: String,
        user: UserHandle,
        group: NotificationChannelGroup,
        modificationType: Int,
    ) {
        notificationHandler.onChannelChanged()
    }

    private data class MutableGroup(
        val id: String?,
        val name: String?,
        val channels: MutableList<ChannelItem>,
    )

    fun getChannelsForApp(packageName: String): List<ChannelGroup> {
        try {
            val user = Process.myUserHandle()
            val groups = getNotificationChannelGroups(packageName, user)
                .map { MutableGroup(it.id, it.name.toString(), mutableListOf()) }
                .associateBy { it.id }.toMutableMap()
            val channels = getNotificationChannels(packageName, user)
            channels.forEach { channel ->
                val channelItem = ChannelItem(
                    id = channel.id,
                    name = channel.name.toString(),
                    muteState = MuteState.Never,
                )
                val group = groups[channel.group]
                if (group == null) {
                    // Some channels don't have groups - but we want them all to have groups.
                    groups[channel.group] = MutableGroup(
                        id = channel.group,
                        name = null,
                        channels = mutableListOf(channelItem),
                    )
                } else {
                    group.channels += channelItem
                }
            }
            return groups.values.map {
                ChannelGroup(
                    id = it.id ?: "default",
                    name = it.name,
                    channels = it.channels.toList(),
                )
            }
        } catch (e: Exception) {
            if (!warnedAboutNotificationchannelsPermission) {
                warnedAboutNotificationchannelsPermission = true
                logger.w("getChannelsFor", e)
            }
            return emptyList()
        }
    }

    // Note (see above comments), if onListenerConnected was called twice, then so will this be, for
    // *every* notification. So - the handler must be resilient to this.
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!configHolder.value.sendNotifications) {
            logger.v { "Notification from ${sbn.packageName} filtered - sendNotifications is off" }
            return
        }

        if (configHolder.value.respectDoNotDisturb && isNotificationFilteredByDoNotDisturb(sbn)) {
            logger.v { "Notification from ${sbn.packageName} filtered - do not disturb" }
            return
        }

        notificationHandler.handleNotificationPosted(sbn)
    }

    override fun onNotificationRemoved(
        sbn: StatusBarNotification,
        rankingMap: RankingMap,
        reason: Int
    ) {
        notificationHandler.handleNotificationRemoved(sbn)
    }

    private fun controlListenerHints() = notificationListenerScope.launch {
        val anyWatchConnected = watches.watches
            .map { watchList -> watchList.any { it is ConnectedPebbleDevice } }
            .distinctUntilChanged()

        val notificationConfig = configHolder.flow.map { it.notificationConfig }
            .distinctUntilChanged()

        combine(anyWatchConnected, notificationConfig) { connected, config ->
            var listenerHints = 0
            if (connected && config.mutePhoneNotificationSoundsWhenConnected) {
                listenerHints = listenerHints or HINT_HOST_DISABLE_NOTIFICATION_EFFECTS
            }

            if (connected && config.mutePhoneCallSoundsWhenConnected) {
                listenerHints = listenerHints or HINT_HOST_DISABLE_CALL_EFFECTS
            }

            listenerHints
        }.distinctUntilChanged().collect {
            try {
                requestListenerHints(it)
            } catch (e: SecurityException) {
                logger.e(e) {"error setting listener hints" }
            }
        }
    }

    fun isNotificationFilteredByDoNotDisturb(statusBarNotification: StatusBarNotification): Boolean {
        val rankingMap = getCurrentRanking() ?: return false

        val ranking = Ranking()
        return rankingMap.getRanking(statusBarNotification.getKey(), ranking) &&
                !ranking.matchesInterruptionFilter()
    }
}
