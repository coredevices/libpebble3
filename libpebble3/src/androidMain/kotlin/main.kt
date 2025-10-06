package io.rebble.libpebblecommon

import io.rebble.libpebblecommon.packets.PhoneAppVersion
import io.rebble.libpebblecommon.pebblekit.PebbleKitClassicStartListeners
import io.rebble.libpebblecommon.pebblekit.PebbleKitProviderNotifier

actual fun getPlatform(): PhoneAppVersion.OSType = PhoneAppVersion.OSType.Android

actual fun performPlatformSpecificInit() {
    PebbleKitClassicStartListeners().init()
    PebbleKitProviderNotifier().init()
}
