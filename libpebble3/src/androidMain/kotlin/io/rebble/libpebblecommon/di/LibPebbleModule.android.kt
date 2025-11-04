package io.rebble.libpebblecommon.di

import android.app.Application
import io.rebble.libpebblecommon.calendar.AndroidSystemCalendar
import io.rebble.libpebblecommon.calendar.SystemCalendar
import io.rebble.libpebblecommon.calls.SystemCallLog
import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.OtherPebbleApps
import io.rebble.libpebblecommon.connection.PhoneCapabilities
import io.rebble.libpebblecommon.connection.PlatformFlags
import io.rebble.libpebblecommon.connection.bt.ble.BlePlatformConfig
import io.rebble.libpebblecommon.connection.bt.classic.pebble.BtClassicConnector
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.AndroidNotificationActionHandler
import io.rebble.libpebblecommon.connection.endpointmanager.timeline.PlatformNotificationActionHandler
import io.rebble.libpebblecommon.contacts.SystemContacts
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.calls.AndroidSystemCallLog
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.connection.bt.classic.transport.AndroidBtClassicConnector
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.contacts.AndroidSystemContacts
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.music.AndroidSystemMusicControl
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification.AndroidNotificationAppsSync
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification.AndroidPackageChangedReceiver
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification.AndroidPebbleNotificationListenerConnection
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.notification.NotificationHandler
import io.rebble.libpebblecommon.io.rebble.libpebblecommon.util.AndroidSystemGeolocation
import io.rebble.libpebblecommon.music.SystemMusicControl
import io.rebble.libpebblecommon.notification.NotificationAppsSync
import io.rebble.libpebblecommon.notification.NotificationListenerConnection
import io.rebble.libpebblecommon.notification.processor.BasicNotificationProcessor
import io.rebble.libpebblecommon.packets.PhoneAppVersion
import io.rebble.libpebblecommon.packets.ProtocolCapsFlag
import io.rebble.libpebblecommon.pebblekit.PebbleKitClassicStartListeners
import io.rebble.libpebblecommon.pebblekit.PebbleKitProviderNotifier
import io.rebble.libpebblecommon.util.OtherPebbleAndroidApps
import io.rebble.libpebblecommon.util.SystemGeolocation
import org.koin.core.module.Module
import org.koin.core.module.dsl.scopedOf
import org.koin.core.module.dsl.singleOf
import org.koin.dsl.bind
import org.koin.dsl.module

actual val platformModule: Module = module {
    single {
        PhoneCapabilities(
            setOf(
                ProtocolCapsFlag.SupportsAppRunStateProtocol,
                ProtocolCapsFlag.SupportsInfiniteLogDump,
                ProtocolCapsFlag.SupportsExtendedMusicProtocol,
                ProtocolCapsFlag.SupportsTwoWayDismissal,
                ProtocolCapsFlag.SupportsAppDictation,
                //ProtocolCapsFlag.SupportsLocalization
                ProtocolCapsFlag.Supports8kAppMessage,
//                ProtocolCapsFlag.SupportsHealthInsights,
//                ProtocolCapsFlag.SupportsAppDictation,
//                ProtocolCapsFlag.SupportsUnreadCoreDump,
//                ProtocolCapsFlag.SupportsWeatherApp,
//                ProtocolCapsFlag.SupportsRemindersApp,
//                ProtocolCapsFlag.SupportsWorkoutApp,
//                ProtocolCapsFlag.SupportsSmoothFwInstallProgress,
//                ProtocolCapsFlag.SupportsFwUpdateAcrossDisconnection,
            )
        )
    }
    single {
        PlatformFlags(
            PhoneAppVersion.PlatformFlag.makeFlags(PhoneAppVersion.OSType.Android, emptyList())
        )
    }
    singleOf(::AndroidPebbleNotificationListenerConnection) bind NotificationListenerConnection::class
    singleOf(::AndroidNotificationActionHandler) bind PlatformNotificationActionHandler::class
    singleOf(::AndroidNotificationAppsSync) bind NotificationAppsSync::class
    singleOf(::AndroidSystemCalendar) bind SystemCalendar::class
    singleOf(::AndroidSystemCallLog) bind SystemCallLog::class
    singleOf(::AndroidSystemMusicControl) bind SystemMusicControl::class
    singleOf(::AndroidSystemGeolocation) bind SystemGeolocation::class
    singleOf(::AndroidPackageChangedReceiver)
    singleOf(::OtherPebbleAndroidApps) bind OtherPebbleApps::class
    singleOf(::AndroidSystemContacts) bind SystemContacts::class
    single { get<AppContext>().context }
    single { get<AppContext>().context as Application }
    single { NotificationHandler(setOf(get<BasicNotificationProcessor>()), get(), get(), get(), get(), get(), get(), get(), get()) }
    singleOf(::BasicNotificationProcessor)
    single { get<Application>().contentResolver }
    single { PlatformConfig(syncNotificationApps = false) }
    single { BlePlatformConfig(
        delayBleConnectionsAfterAppStart = true,
        delayBleDisconnections = true,
        supportsBtClassic = true,
    ) }

    scope<ConnectionScope> {
        scopedOf(::AndroidBtClassicConnector) bind BtClassicConnector::class
    }

    single { PebbleKitClassicStartListeners(get(), get(), get()) }
    single { PebbleKitProviderNotifier(get<LibPebble>(), get(), get()) }
}
