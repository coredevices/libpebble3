package io.rebble.libpebblecommon

import io.rebble.libpebblecommon.di.LibPebbleKoinComponent
import io.rebble.libpebblecommon.packets.PhoneAppVersion
import io.rebble.libpebblecommon.pebblekit.PebbleKitClassicStartListeners
import io.rebble.libpebblecommon.pebblekit.PebbleKitProviderNotifier

actual fun getPlatform(): PhoneAppVersion.OSType = PhoneAppVersion.OSType.Android

actual fun performPlatformSpecificInit() {
    val koin = object: LibPebbleKoinComponent {}.getKoin()
    koin.get<PebbleKitClassicStartListeners>().init()
    koin.get<PebbleKitProviderNotifier>().init()
}
