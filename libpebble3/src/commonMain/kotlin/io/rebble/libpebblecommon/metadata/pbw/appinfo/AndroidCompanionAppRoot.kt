package io.rebble.libpebblecommon.metadata.pbw.appinfo

import kotlinx.serialization.Serializable

@Serializable
data class AndroidCompanionAppRoot(
    val url: String? = null,
    val apps: List<AndroidCompanionAppInstance> = emptyList()
)
