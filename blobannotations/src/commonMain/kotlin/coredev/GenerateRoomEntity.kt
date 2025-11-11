package coredev

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.SOURCE)
annotation class GenerateRoomEntity(
    val primaryKey: String,
    val databaseId: BlobDatabase,
    /** Assuming there is a [timestamp] field, how long before timestamp we should sync to watch */
    val windowBeforeSecs: Long,
    /** Assuming there is a [timestamp] field, how long after timestamp we should sync to watch */
    val windowAfterSecs: Long,
    /** Only process inserts for records with [timestamp] after :insertOnlyAfterMs */
    val onlyInsertAfter: Boolean,
    /** Send deletions to watch? We never want to e.g. for notifications */
    val sendDeletions: Boolean,
)

// TODO move somewhere better
enum class BlobDatabase(val id: UByte, val sendClear: Boolean = true) {
    Test(0u),
    Pin(1u),
    App(2u),
    Reminder(3u),
    Notification(4u),
    Weather(5u),
    CannedResponses(6u),
    HealthParams(7u),
    Contacts(8u),
    AppConfigs(9u),
    HealthStats(10u),
    AppGlance(11u),
    WatchPrefs(12u, sendClear = false),
    Invalid(0xFFu),
    ;
    companion object {
        fun from(id: UByte): BlobDatabase = entries.firstOrNull { it.id == id } ?: Invalid
    }
}
