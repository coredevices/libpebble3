package io.rebble.libpebblecommon.io.rebble.libpebblecommon.js

import io.rebble.libpebblecommon.connection.AppContext
import io.rebble.libpebblecommon.js.JSLocalStorageInterface
import io.rebble.libpebblecommon.js.JsRunner
import io.rebble.libpebblecommon.js.RegisterableJsInterface
import io.rebble.libpebblecommon.js.get
import io.rebble.libpebblecommon.js.set
import platform.JavaScriptCore.JSContext
import platform.JavaScriptCore.JSManagedValue
import platform.JavaScriptCore.JSValue

class JSCJSLocalStorageInterface(
    private val jsContext: JSContext,
    scopedSettingsUuid: String,
    appContext: AppContext,
    private val evalRaw: (String) -> JSValue?
): JSLocalStorageInterface(scopedSettingsUuid, appContext), RegisterableJsInterface {
    override val interf = mapOf(
        "getItem" to this::getItem,
        "setItem" to this::setItem,
        "removeItem" to this::removeItem,
        "clear" to this::clear,
        "key" to this::key
    )
    override val name = "localStorage"
    private lateinit var localStorage: JSManagedValue
    override fun onRegister(jsContext: JSContext) {
        localStorage = JSManagedValue(jsContext["localStorage"]!!)
        jsContext.virtualMachine!!.addManagedReference(localStorage, this)
        setLength(getLength())
        localStorage.value?.set("__override__", true)
    }

    override fun getItem(key: Any?): Any? {
        return super.getItem(key) ?: evalRaw("null")
    }

    override fun setLength(value: Int) {
        localStorage.value?.set("length", value)
    }

    override fun close() {
        jsContext.virtualMachine?.removeManagedReference(localStorage, this)
    }

}