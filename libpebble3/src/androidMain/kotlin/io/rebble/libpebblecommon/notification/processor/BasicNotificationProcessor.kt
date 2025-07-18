package io.rebble.libpebblecommon.notification.processor

import android.app.Notification
import android.service.notification.StatusBarNotification
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.NotificationConfigFlow
import io.rebble.libpebblecommon.database.entity.ChannelItem
import io.rebble.libpebblecommon.database.entity.NotificationAppItem
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification.LibPebbleNotification
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification.NotificationProcessor
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification.NotificationResult
import io.rebble.libpebblecommon.notification.NotificationDecision
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import kotlinx.datetime.Instant
import kotlin.uuid.Uuid

class BasicNotificationProcessor(
    private val notificationConfigFlow: NotificationConfigFlow,
) : NotificationProcessor {
    private val logger = Logger.withTag("BasicNotificationProcessor")

    override fun extractNotification(
        sbn: StatusBarNotification,
        app: NotificationAppItem,
        channel: ChannelItem?,
    ): NotificationResult {
        // Note: the "if (inflightNotifications.values..." check in [NotificationHandler] is
        // effectively doing the deduping right now. I'm sure we'll find cases where it isn't, but
        // let's try that for now.
        val actions = LibPebbleNotification.actionsFromStatusBarNotification(sbn, app, channel, notificationConfigFlow.value)
        val title = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE) ?: ""
        val text = sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)
        val bigText = sbn.notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
        val showWhen = sbn.notification.extras.getBoolean(Notification.EXTRA_SHOW_WHEN)
        val body = bigText ?: text ?: ""
        val notification = LibPebbleNotification(
            packageName = sbn.packageName,
            uuid = Uuid.random(),
            groupKey = sbn.groupKey,
            key = sbn.key,
            title = title.toString(),
            body = body.toString(),
            icon = sbn.icon(),
            timestamp = if (showWhen) {
                Instant.fromEpochMilliseconds(sbn.notification.`when`)
            } else {
                Instant.fromEpochMilliseconds(sbn.postTime)
            },
            actions = actions,
        )
        return NotificationResult.Extracted(notification, NotificationDecision.SendToWatch)
    }
}

fun StatusBarNotification.icon(): TimelineIcon = when(packageName) {
    "com.google.android.gm.lite", "com.google.android.gm" -> TimelineIcon.NotificationGmail
    "com.microsoft.office.outlook" -> TimelineIcon.NotificationOutlook
    "com.Slack" -> TimelineIcon.NotificationSlack
    "com.snapchat.android" -> TimelineIcon.NotificationSnapchat
    "com.twitter.android", "com.twitter.android.lite" -> TimelineIcon.NotificationTwitter
    "org.telegram.messenger" -> TimelineIcon.NotificationTelegram
    "com.facebook.katana", "com.facebook.lite" -> TimelineIcon.NotificationFacebook
    "com.facebook.orca" -> TimelineIcon.NotificationFacebookMessenger
    "com.whatsapp" -> TimelineIcon.NotificationWhatsapp
    "com.linkedin.android" -> TimelineIcon.NotificationLinkedIn
    "com.google.android.apps.messaging" -> TimelineIcon.NotificationGoogleMessenger
    "com.tencent.mm" -> TimelineIcon.NotificationWeChat
    "com.microsoft.office.lync" -> TimelineIcon.NotificationSkype
    "jp.naver.line.android" -> TimelineIcon.NotificationLine
    "com.amazon.mShop.android.shopping" -> TimelineIcon.NotificationAmazon
    "com.google.android.apps.maps" -> TimelineIcon.NotificationGoogleMaps
    "com.yahoo.mobile.client.android.mail" -> TimelineIcon.NotificationYahooMail
    "com.google.android.apps.photos" -> TimelineIcon.NotificationGooglePhotos
    "com.viber.voip" -> TimelineIcon.NotificationViber
    "com.instagram.android" -> TimelineIcon.NotificationInstagram
    "com.bbm.enterprise" -> TimelineIcon.NotificationBlackberryMessenger
    "com.google.android.apps.dynamite" -> TimelineIcon.NotificationGoogleHangouts
    "kik.android" -> TimelineIcon.NotificationKik
    "com.kakao.talk" -> TimelineIcon.NotificationKakaoTalk
    "com.beeper.android" -> TimelineIcon.GenericSms
    "ch.protonmail.android" -> TimelineIcon.GenericEmail

    else -> when (notification.category) {
        Notification.CATEGORY_EMAIL -> TimelineIcon.GenericEmail
        Notification.CATEGORY_MESSAGE -> TimelineIcon.GenericSms
        Notification.CATEGORY_EVENT -> TimelineIcon.TimelineCalendar
        Notification.CATEGORY_PROMO -> TimelineIcon.PayBill
        Notification.CATEGORY_ALARM -> TimelineIcon.AlarmClock
        Notification.CATEGORY_ERROR -> TimelineIcon.GenericWarning
        Notification.CATEGORY_TRANSPORT -> TimelineIcon.AudioCassette
        Notification.CATEGORY_SYSTEM -> TimelineIcon.Settings
        Notification.CATEGORY_REMINDER -> TimelineIcon.NotificationReminder
        Notification.CATEGORY_WORKOUT -> TimelineIcon.Activity
        Notification.CATEGORY_MISSED_CALL -> TimelineIcon.TimelineMissedCall
        Notification.CATEGORY_CALL -> TimelineIcon.IncomingPhoneCall
        Notification.CATEGORY_NAVIGATION, Notification.CATEGORY_LOCATION_SHARING -> TimelineIcon.Location
        Notification.CATEGORY_SOCIAL, Notification.CATEGORY_RECOMMENDATION -> TimelineIcon.NewsEvent
        else -> TimelineIcon.NotificationGeneric
    }
}