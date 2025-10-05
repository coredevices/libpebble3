package io.rebble.libpebblecommon.pebblekit

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import androidx.core.net.toUri
import io.rebble.libpebblecommon.connection.ConnectedPebbleDevice
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.connection.Watches
import io.rebble.libpebblecommon.di.LibPebbleCoroutineScope
import io.rebble.libpebblecommon.di.LibPebbleKoinComponent
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class PebbleKitProvider : ContentProvider(), LibPebbleKoinComponent {
    private var initialized = false
    private lateinit var watches: Watches
    private lateinit var libPebbleCoroutineScope: LibPebbleCoroutineScope
    override fun onCreate(): Boolean {
        // Do not initialize anything here as this gets called before Application.onCreate, so LibPebble has not yet been
        // initialized at this point

        return true
    }

    private fun initializeIfNeeded() {
        if (initialized) {
            return
        }

        val context = context
            ?: throw IllegalStateException("Context should not be null when initializing")

        watches = getKoin().get<LibPebble>()
        libPebbleCoroutineScope = getKoin().get()

        initialized = true

        libPebbleCoroutineScope.launch {
            watches.watches.map {
                it.any { it is ConnectedPebbleDevice }
            }
                .distinctUntilChanged()
                .collect {
                    context.contentResolver.notifyChange(URI_CONTENT_BASALT, null)
                }
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        if (uri != URI_CONTENT_BASALT) {
            return null
        }

        initializeIfNeeded()

        val cursor = MatrixCursor(CURSOR_COLUMN_NAMES)

        val connectedWatch = watches.watches.value.filterIsInstance<ConnectedPebbleDevice>().firstOrNull()

        if (connectedWatch != null) {
            val fwVersion = connectedWatch.watchInfo.runningFwVersion
            cursor.addRow(
                listOf(
                    1, // Connected
                    1, // App Message support
                    0, // Data Logging support
                    fwVersion.major, // Major version support
                    fwVersion.minor, // Minor version support
                    fwVersion.patch, // Point version support
                    fwVersion.suffix, // Version Tag
                )
            )
        } else {
            cursor.addRow(
                listOf(
                    0, // Connected
                    0, // App Message support
                    0, // Data Logging support
                    0, // Major version support
                    0, // Minor version support
                    0, // Point version support
                    "", // Version Tag
                )
            )
        }

        return cursor
    }

    override fun getType(uri: Uri): String? {
        return null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        // This provider is read-only
        return null
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int {
        // This provider is read-only
        return 0
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        // This provider is read-only
        return 0
    }
}

private val CURSOR_COLUMN_NAMES = arrayOf(
    KIT_STATE_COLUMN_CONNECTED.toString(),
    KIT_STATE_COLUMN_APPMSG_SUPPORT.toString(),
    KIT_STATE_COLUMN_DATALOGGING_SUPPORT.toString(),
    KIT_STATE_COLUMN_VERSION_MAJOR.toString(),
    KIT_STATE_COLUMN_VERSION_MINOR.toString(),
    KIT_STATE_COLUMN_VERSION_POINT.toString(),
    KIT_STATE_COLUMN_VERSION_TAG.toString()
)


private val URI_CONTENT_BASALT = "content://com.getpebble.android.provider.basalt/state".toUri()

private const val KIT_STATE_COLUMN_CONNECTED = 0

private const val KIT_STATE_COLUMN_APPMSG_SUPPORT = 1

private const val KIT_STATE_COLUMN_DATALOGGING_SUPPORT = 2

private const val KIT_STATE_COLUMN_VERSION_MAJOR = 3

private const val KIT_STATE_COLUMN_VERSION_MINOR = 4

private const val KIT_STATE_COLUMN_VERSION_POINT = 5

private const val KIT_STATE_COLUMN_VERSION_TAG = 6

