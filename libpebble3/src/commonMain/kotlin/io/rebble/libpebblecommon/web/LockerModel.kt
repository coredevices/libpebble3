package io.rebble.libpebblecommon.web

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LockerModel(
    val applications: List<LockerEntry>,
    val nextPageURL: String? = null,
)

@Serializable
data class LockerEntry(
    val id: String,
    val uuid: String,
    @SerialName("user_token") val userToken: String? = null,
    val title: String,
    val type: String,
    val category: String,
    val version: String? = null,
    val hearts: Int,
    @SerialName("is_configurable") val isConfigurable: Boolean,
    @SerialName("is_timeline_enabled") val isTimelineEnabled: Boolean?,
    val links: LockerEntryLinks,
    val developer: LockerEntryDeveloper,
    @SerialName("hardware_platforms") val hardwarePlatforms: List<LockerEntryPlatform>,
    val compatibility: LockerEntryCompatibility,
    val companions: LockerEntryCompanions,
    val pbw: LockerEntryPBW? = null
)

@Serializable
data class LockerEntryLinks(
    val remove: String,
    val href: String,
    val share: String
)

@Serializable
data class LockerEntryDeveloper(
    val id: String,
    val name: String,
    @SerialName("contact_email") val contactEmail: String
)

@Serializable
data class LockerEntryPlatform(
    @SerialName("sdk_version") val sdkVersion: String,
    @SerialName("pebble_process_info_flags") val pebbleProcessInfoFlags: Int,
    val name: String,
    val description: String,
    val images: LockerEntryPlatformImages
)

@Serializable
data class LockerEntryPlatformImages(
    val icon: String,
    val list: String,
    val screenshot: String
)

@Serializable
data class LockerEntryCompatibility(
    val ios: LockerEntryCompatibilityPhonePlatformDetails,
    val android: LockerEntryCompatibilityPhonePlatformDetails,
    val aplite: LockerEntryCompatibilityWatchPlatformDetails,
    val basalt: LockerEntryCompatibilityWatchPlatformDetails,
    val chalk: LockerEntryCompatibilityWatchPlatformDetails,
    val diorite: LockerEntryCompatibilityWatchPlatformDetails,
    val emery: LockerEntryCompatibilityWatchPlatformDetails,
    val flint: LockerEntryCompatibilityWatchPlatformDetails? = null,
)

@Serializable
data class LockerEntryCompatibilityPhonePlatformDetails(
    val supported: Boolean,
    @SerialName("min_js_version") val minJsVersion: Int? = null
)

@Serializable
data class LockerEntryCompatibilityWatchPlatformDetails(
    val supported: Boolean,
    val firmware: LockerEntryFirmwareVersion
)

@Serializable
data class LockerEntryFirmwareVersion(
    val major: Int,
    val minor: Int? = null,
    val patch: Int? = null
)

@Serializable
data class LockerEntryCompanions(
    val android: LockerEntryCompanionApp?,
    val ios: LockerEntryCompanionApp?,
)

@Serializable
data class LockerEntryCompanionApp(
    val id: Int,
    val icon: String,
    val name: String,
    val url: String,
    val required: Boolean,
    @SerialName("pebblekit_version") val pebblekitVersion: String
)

@Serializable
data class LockerEntryPBW(
    val file: String,
    @SerialName("icon_resource_id") val iconResourceId: Int,
    @SerialName("release_id") val releaseId: String
)
