package io.rebble.libpebblecommon.connection.devconnection

import android.content.Context
import io.rebble.libpebblecommon.di.LibPebbleKoinComponent
import kotlinx.io.files.Path
import java.util.UUID

internal actual fun getTempPbwPath(): Path {
    val koin = object: LibPebbleKoinComponent {}.getKoin()
    val context: Context = koin.get()
    val cacheDir = context.cacheDir
    val uuid = UUID.randomUUID().toString()
    return Path(cacheDir.absolutePath, "devconn-$uuid.pbw")
}
