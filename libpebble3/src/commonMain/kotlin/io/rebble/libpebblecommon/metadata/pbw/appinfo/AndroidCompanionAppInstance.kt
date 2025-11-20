package io.rebble.libpebblecommon.metadata.pbw.appinfo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AndroidCompanionAppInstance(
    @SerialName("package") val pkg: String? = null
)
