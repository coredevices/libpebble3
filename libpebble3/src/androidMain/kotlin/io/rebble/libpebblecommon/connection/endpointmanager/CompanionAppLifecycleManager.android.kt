package io.rebble.libpebblecommon.connection.endpointmanager

import io.rebble.libpebblecommon.connection.CompanionApp
import io.rebble.libpebblecommon.js.CompanionAppDevice
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import io.rebble.libpebblecommon.pebblekit.classic.PebbleKitClassic
import io.rebble.libpebblecommon.pebblekit.two.PebbleKit2

actual fun createPlatformSpecificCompanionAppControl(
    device: CompanionAppDevice,
    appInfo: PbwAppInfo
): CompanionApp? {
    val hasAnyPebbleKit2CompanionApps =
        appInfo.companionApp
            ?.android
            ?.apps
            ?.any { it.pkg != null } == true

    return if (hasAnyPebbleKit2CompanionApps) {
        PebbleKit2(device, appInfo)
    } else {
        PebbleKitClassic(device, appInfo)
    }
}
