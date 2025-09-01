package io.rebble.libpebblecommon.di

import io.rebble.libpebblecommon.calendar.IosSystemCalendar
import io.rebble.libpebblecommon.calendar.SystemCalendar
import io.rebble.libpebblecommon.calls.SystemCallLog
import io.rebble.libpebblecommon.connection.OtherPebbleApps
import io.rebble.libpebblecommon.connection.PhoneCapabilities
import io.rebble.libpebblecommon.connection.PlatformFlags
import io.rebble.libpebblecommon.connection.bt.ble.BlePlatformConfig
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.IosNotificationActionHandler
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.IosNotificationAppsSync
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.IosNotificationListenerConnection
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.PlatformNotificationActionHandler
import io.rebble.libpebblecommon.contacts.SystemContacts
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.calls.IosSystemCallLog
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.contacts.IosSystemContacts
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.music.IosSystemMusicControl
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.util.IosSystemGeolocation
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.util.OtherPebbleIosApps
import io.rebble.libpebblecommon.music.SystemMusicControl
import io.rebble.libpebblecommon.notification.NotificationAppsSync
import io.rebble.libpebblecommon.notification.NotificationListenerConnection
import io.rebble.libpebblecommon.packets.PhoneAppVersion
import io.rebble.libpebblecommon.packets.ProtocolCapsFlag
import io.rebble.libpebblecommon.util.SystemGeolocation
import org.koin.core.module.Module
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

actual val platformModule: Module = module {
    single {
        PhoneCapabilities(
            setOf(
                ProtocolCapsFlag.SupportsAppRunStateProtocol,
                ProtocolCapsFlag.SupportsInfiniteLogDump,
                //ProtocolCapsFlag.SupportsLocalization
                ProtocolCapsFlag.Supports8kAppMessage,
//                ProtocolCapsFlag.SupportsHealthInsights,
                ProtocolCapsFlag.SupportsNotificationFiltering,
//                ProtocolCapsFlag.SupportsUnreadCoreDump,
//                ProtocolCapsFlag.SupportsWeatherApp,
//                ProtocolCapsFlag.SupportsRemindersApp,
//                ProtocolCapsFlag.SupportsWorkoutApp,
//                ProtocolCapsFlag.SupportsSmoothFwInstallProgress,
//                ProtocolCapsFlag.SupportsFwUpdateAcrossDisconnection,
                ProtocolCapsFlag.SupportsAppDictation
            )
        )
    }
    single {
        PlatformFlags(
            PhoneAppVersion.PlatformFlag.makeFlags(PhoneAppVersion.OSType.IOS, emptyList())
        )
    }
    singleOf(::IosNotificationActionHandler) bind PlatformNotificationActionHandler::class
    singleOf(::IosNotificationListenerConnection) bind NotificationListenerConnection::class
    singleOf(::IosNotificationAppsSync) bind NotificationAppsSync::class
    singleOf(::IosSystemCalendar) bind SystemCalendar::class
    singleOf(::IosSystemCallLog) bind SystemCallLog::class
    singleOf(::IosSystemMusicControl) bind SystemMusicControl::class
    singleOf(::IosSystemGeolocation) bind SystemGeolocation::class
    singleOf(::OtherPebbleIosApps) bind OtherPebbleApps::class
    singleOf(::IosSystemContacts) bind SystemContacts::class
    single { PlatformConfig(syncNotificationApps = true) }
    single { BlePlatformConfig(
        pinAddress = false,
        phoneRequestsPairing = false,
        useNativeMtu = false,
        sendPpogResetOnDisconnection = true,
        fallbackToResetRequest = true,
        closeGattServerWhenBtDisabled = false,
        delayBleConnectionsAfterAppStart = true,
    ) }
}
