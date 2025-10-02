package io.rebble.libpebblecommon

import androidx.annotation.VisibleForTesting
import co.touchlab.kermit.Logger
import com.russhwolf.settings.Settings
import com.russhwolf.settings.set
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

class LibPebbleConfigHolder(
    private val defaultValue: LibPebbleConfig,
    private val settings: Settings,
    private val json: Json,
) {
    private fun defaultValue(): LibPebbleConfig {
        return loadFromStorage() ?: defaultValue.also { saveToStorage(it) }
    }

    private fun loadFromStorage(): LibPebbleConfig? = settings.getStringOrNull(SETTINGS_KEY)?.let { string ->
        try {
            json.decodeFromString(string)
        } catch (e: SerializationException) {
            Logger.w("Error loading settings", e)
            null
        }
    }

    private fun saveToStorage(value: LibPebbleConfig) {
        settings.set(SETTINGS_KEY, json.encodeToString(value))
    }

    fun update(value: LibPebbleConfig) {
        saveToStorage(value)
        _config.value = value
    }

    private val _config: MutableStateFlow<LibPebbleConfig> = MutableStateFlow(defaultValue())
    val config: StateFlow<LibPebbleConfig> = _config.asStateFlow()
}

private const val SETTINGS_KEY = "libpebble.settings"

@Serializable
data class LibPebbleConfig(
    val bleConfig: BleConfig = BleConfig(),
    val watchConfig: WatchConfig = WatchConfig(),
    val notificationConfig: NotificationConfig = NotificationConfig(),
)

class LibPebbleConfigFlow(val flow: StateFlow<LibPebbleConfig>) {
    val value get() = flow.value
}

@Serializable
data class WatchConfig(
    val multipleConnectedWatchesSupported: Boolean = false,
    val lockerSyncLimit: Int = 25,
    val calendarReminders: Boolean = true,
    val enableHealth: Boolean = true,
    val ignoreMissingPrf: Boolean = false,
    val lanDevConnection: Boolean = false,
    val verboseWatchManagerLogging: Boolean = false,
)

class WatchConfigFlow(val flow: StateFlow<LibPebbleConfig>) {
    val value: WatchConfig get() = flow.value.watchConfig
}

@VisibleForTesting
fun WatchConfig.asFlow() = WatchConfigFlow(MutableStateFlow(LibPebbleConfig(watchConfig = this)))

@Serializable
data class BleConfig(
    val reversedPPoG: Boolean = false,
    val verbosePpogLogging: Boolean = false,
)

class BleConfigFlow(val flow: StateFlow<LibPebbleConfig>) {
    val value: BleConfig get() = flow.value.bleConfig
}

@VisibleForTesting
fun BleConfig.asFlow() = BleConfigFlow(MutableStateFlow(LibPebbleConfig(bleConfig = this)))

@Serializable
data class NotificationConfig(
    val dumpNotificationContent: Boolean = true,
    val obfuscateContent: Boolean = true,
    val sendLocalOnlyNotifications: Boolean = false,
    val storeNotifiationsForDays: Int = 7,
    val addShowsUserInterfaceActions: Boolean = false,
    val alwaysSendNotifications: Boolean = true,
    /**
     * Mute all notification sounds on the phone when at least one watch is connected
     */
    val mutePhoneNotificationSoundsWhenConnected: Boolean = false,
    /**
     * Mute all call alerts on the phone when at least one watch is connected
     */
    val mutePhoneCallSoundsWhenConnected: Boolean = false,
    /**
     * When [true], any notifications muted by the phone's do not disturb will not be forwarded to the watch
     */
    val respectDoNotDisturb: Boolean = false,
)

class NotificationConfigFlow(val flow: StateFlow<LibPebbleConfig>) {
    val value: NotificationConfig get() = flow.value.notificationConfig
}

@VisibleForTesting
fun NotificationConfig.asFlow() = NotificationConfigFlow(MutableStateFlow(LibPebbleConfig(notificationConfig = this)))
