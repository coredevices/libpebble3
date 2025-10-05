package io.rebble.libpebblecommon

import io.rebble.libpebblecommon.packets.PhoneAppVersion
import io.rebble.libpebblecommon.pebblekit.PebbleKitClassicStartListeners

actual fun getPlatform(): PhoneAppVersion.OSType = PhoneAppVersion.OSType.Android

actual fun performPlatformSpecificInit() {
    PebbleKitClassicStartListeners().init()
}
