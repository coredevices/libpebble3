package io.rebble.libpebblecommon.connection

import android.app.Activity
import android.content.Context

actual class AppContext(val context: Context)


actual class UIContext(val activity: Activity)