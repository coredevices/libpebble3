package io.rebble.libpebblecommon.metadata.pbw.appinfo

import kotlinx.serialization.Serializable

@Serializable
data class CompanionApp(val android: AndroidCompanionAppRoot? = null)
