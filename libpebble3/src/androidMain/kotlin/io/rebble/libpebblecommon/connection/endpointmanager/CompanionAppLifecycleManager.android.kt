package io.rebble.libpebblecommon.connection.endpointmanager

import io.rebble.libpebblecommon.connection.CompanionApp
import io.rebble.libpebblecommon.js.CompanionAppDevice
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo
import io.rebble.libpebblecommon.pebblekit.PebbleKitClassic

actual fun createPlatformSpecificCompanionAppControl(
    device: CompanionAppDevice,
    appInfo: PbwAppInfo
): CompanionApp? {
    return PebbleKitClassic(device, appInfo)
}
