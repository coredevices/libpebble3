package io.rebble.libpebblecommon.connection.endpointmanager

import io.rebble.libpebblecommon.connection.CompanionApp
import io.rebble.libpebblecommon.js.CompanionAppDevice
import io.rebble.libpebblecommon.metadata.pbw.appinfo.PbwAppInfo

actual fun createPlatformSpecificCompanionAppControl(
    device: CompanionAppDevice,
    appInfo: PbwAppInfo
): CompanionApp? {
    // PebbleKit iOS does not work through the phone - nothing to do here
    return null
}
