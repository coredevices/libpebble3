package io.rebble.libpebblecommon.js

import platform.JavaScriptCore.JSContext

interface RegisterableJsInterface: AutoCloseable {
    val interf: Map<String, *>
    val name: String
    fun onRegister(jsContext: JSContext) {}
}