package io.rebble.libpebblecommon

import io.rebble.libpebblecommon.packets.PhoneAppVersion

expect fun getPlatform(): PhoneAppVersion.OSType

expect fun performPlatformSpecificInit()
