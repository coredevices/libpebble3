package io.rebble.libpebblecommon.metadata.pbw.appinfo

import kotlinx.serialization.Serializable

@Serializable
data class PbwAppInfo(
    val uuid: String,
    val shortName: String,
    val longName: String = "",
    val companyName: String = "",
    val versionCode: Float = -1f,
    val versionLabel: String,
    val appKeys: Map<String, Int> = emptyMap(),
    val capabilities: List<String> = emptyList(),
    val resources: Resources,
    val sdkVersion: String = "3",
    // If list of target platforms is not present, pbw is legacy applite app
    val targetPlatforms: List<String> = listOf("aplite"),
    val watchapp: Watchapp = Watchapp(),
    val companionApp: CompanionApp? = null,
)
