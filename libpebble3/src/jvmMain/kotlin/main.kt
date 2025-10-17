package io.rebble.libpebblecommon

import io.rebble.libpebblecommon.packets.PhoneAppVersion

actual fun getPlatform(): PhoneAppVersion.OSType = PhoneAppVersion.OSType.Unknown

actual fun performPlatformSpecificInit() {}
