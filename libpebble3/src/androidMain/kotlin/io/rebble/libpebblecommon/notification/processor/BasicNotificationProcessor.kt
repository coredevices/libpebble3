package io.rebble.libpebblecommon.notification.processor

import android.app.Notification
import android.app.Person
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.service.notification.StatusBarNotification
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.NotificationConfigFlow
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.database.entity.ChannelItem
import io.rebble.libpebblecommon.database.entity.NotificationAppItem
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification.LibPebbleNotification
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification.NotificationProcessor
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification.NotificationResult
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification.people
import io.rebble.libpebblecommon.notification.NotificationDecision
import io.rebble.libpebblecommon.packets.blobdb.TimelineIcon
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val logger = Logger.withTag("BasicNotificationProcessor")

class BasicNotificationProcessor(
    private val notificationConfigFlow: NotificationConfigFlow,
    private val context: AppContext,
) : NotificationProcessor {
    override fun extractNotification(
        sbn: StatusBarNotification,
        app: NotificationAppItem,
        channel: ChannelItem?,
    ): NotificationResult {
        // Note: the "if (inflightNotifications.values..." check in [NotificationHandler] is
        // effectively doing the deduping right now. I'm sure we'll find cases where it isn't, but
        // let's try that for now.
        val actions = LibPebbleNotification.actionsFromStatusBarNotification(
            sbn,
            app,
            channel,
            notificationConfigFlow.value
        )
        val title = sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE) ?: ""
        val text = sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)
        val bigText = sbn.notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
        val showWhen = sbn.notification.extras.getBoolean(Notification.EXTRA_SHOW_WHEN)
        val body = bigText ?: text ?: ""
        val people = sbn.notification.people()
        val contactKeys = people.asContacts(context.context)
        val color = HardcodedNotificationColors.packageColorMap[sbn.packageName]
            ?: sbn.notification.color.takeIf { it != 0 && it != 0xFF000000.toInt() }
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
            people = contactKeys,
            color = color,
        )
        return NotificationResult.Extracted(notification, NotificationDecision.SendToWatch)
    }
}

private fun lookupKeyFromCursor(cursor: Cursor): String? {
    if (!cursor.moveToFirst()) {
        return null
    }
    val lookupKeyIndex =
        cursor.getColumnIndex(ContactsContract.PhoneLookup.LOOKUP_KEY)
    if (lookupKeyIndex == -1) {
        logger.w { "asContacts: No lookup key index" }
        return null
    }
    return cursor.getString(lookupKeyIndex)
}

private fun Uri.lookupContactTel(context: Context): String? {
    val phoneNumber = schemeSpecificPart
    if (phoneNumber.isNullOrEmpty()) {
        logger.w { "asContacts: Empty phone number from tel URI" }
        return null
    }
    val phoneLookupUri = Uri.withAppendedPath(
        ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
        Uri.encode(phoneNumber)
    )
    val projection = arrayOf(ContactsContract.PhoneLookup.LOOKUP_KEY)
    context.contentResolver.query(phoneLookupUri, projection, null, null, null)?.use { cursor ->
        return lookupKeyFromCursor(cursor)
    }
    return null
}

private fun Uri.lookupContactMailto(context: Context): String? {
    val emailAddress = schemeSpecificPart
    if (emailAddress.isNullOrEmpty()) {
        logger.w { "asContacts: Empty phone number from mailto URI" }
        return null
    }
    val emailProjection = arrayOf(ContactsContract.CommonDataKinds.Email.LOOKUP_KEY)
    context.contentResolver.query(
        ContactsContract.CommonDataKinds.Email.CONTENT_URI,
        emailProjection,
        "${ContactsContract.CommonDataKinds.Email.ADDRESS} = ?",
        arrayOf(emailAddress),
        null // No specific sort order needed for just getting the key
    )?.use { cursor ->
        return lookupKeyFromCursor(cursor)
    }
    return null
}

private fun Uri.lookupContent(context: Context): String? {
    val contactUri: Uri? = ContactsContract.Contacts.lookupContact(context.contentResolver, this)
    if (contactUri == null) {
        logger.w { "asContacts: null contactUri" }
        return null
    }
    context.contentResolver.query(
        contactUri,
        arrayOf(ContactsContract.Contacts.LOOKUP_KEY),
        null, null, null
    )?.use { cursor ->
        return lookupKeyFromCursor(cursor)
    }
    return null
}

private fun lookupKey(key: String?, context: Context): String? {
    if (key == null) {
        return null
    }
    context.contentResolver.query(
        ContactsContract.Contacts.CONTENT_URI,
        arrayOf(ContactsContract.Contacts.LOOKUP_KEY),
        "${ContactsContract.Contacts.LOOKUP_KEY} = ?",
        arrayOf(key),
        null
    )?.use { cursor ->
        return lookupKeyFromCursor(cursor)
    }
    return null
}

private fun List<Person>.asContacts(context: Context): List<String> = mapNotNull { person ->
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
        return@mapNotNull null
    }
    val lookupUri = person.uri?.let { Uri.parse(it) }
    if (lookupUri == null) {
        logger.v { "asContacts: null lookupUri" }
        return@mapNotNull null
    }
    when (lookupUri.scheme) {
        "tel" -> lookupUri.lookupContactTel(context)
        "mailto" -> lookupUri.lookupContactMailto(context)
        "content" -> lookupUri.lookupContent(context)
        else -> lookupKey(person.key, context)
    }
}

fun StatusBarNotification.icon(): TimelineIcon = when (packageName) {
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
    "me.proton.android.calendar" -> TimelineIcon.TimelineCalendar
    "com.google.android.apps.walletnfcrel" -> TimelineIcon.PayBill
    "com.google.android.youtube" -> TimelineIcon.TvShow // Use until the YouTube icon is in the fw repo
    "app.revanced.android.youtube" -> TimelineIcon.TvShow // Use until the YouTube icon is in the fw repo

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