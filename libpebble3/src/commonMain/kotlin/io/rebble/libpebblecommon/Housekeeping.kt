package io.rebble.libpebblecommon

import io.rebble.libpebblecommon.database.dao.NotificationDao
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

class Housekeeping(
    private val libPebbleCoroutineScope: LibPebbleCoroutineScope,
    private val notificationsDao: NotificationDao,
    private val notificationConfigFlow: NotificationConfigFlow,
    private val clock: Clock,
) {
    fun init() {
        libPebbleCoroutineScope.launch {
            while (true) {
                doHousekeeping()
                delay(6.hours)
            }
        }
    }

    private suspend fun doHousekeeping() {
        val deleteNotificationsOlderThan = clock.now() - notificationConfigFlow.value.storeNotifiationsForDays.days
        notificationsDao.deleteOldNotifications(deleteNotificationsOlderThan.toEpochMilliseconds())
    }
}