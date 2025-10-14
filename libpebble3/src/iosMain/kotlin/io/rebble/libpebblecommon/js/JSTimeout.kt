package io.rebble.libpebblecommon.js

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import platform.JavaScriptCore.JSContext
import platform.JavaScriptCore.JSManagedValue
import platform.JavaScriptCore.JSValue
import kotlin.time.Duration.Companion.milliseconds

class JSTimeout(private val scope: CoroutineScope, private val evalRaw: (String) -> JSValue?): RegisterableJsInterface {
    private val timeouts = mutableMapOf<Int, Job>()
    private var timeoutIDs = (1..Int.MAX_VALUE).iterator()
    private val jsFunTriggerTimeout: JSManagedValue get() = JSManagedValue(evalRaw("globalThis._LibPebbleTriggerTimeout")!!)
    private val jsFunTriggerInterval: JSManagedValue get() = JSManagedValue(evalRaw("globalThis._LibPebbleTriggerInterval")!!)
    override val interf = mapOf(
        "setTimeout" to this::setTimeout,
        "setInterval" to this::setInterval,
        "clearTimeout" to this::clearTimeout,
        "clearInterval" to this::clearInterval
    )
    override val name = "_Timeout"

    private fun triggerTimeout(id: Int) {
        jsFunTriggerTimeout.value?.callWithArguments(listOf(id.toDouble()))
    }
    private fun triggerInterval(id: Int) {
        jsFunTriggerInterval.value?.callWithArguments(listOf(id.toDouble()))
    }

    fun setTimeout(delay: Double): Double {
        val id = getNextTimeoutID()
        val job = scope.launch {
            delay(delay.milliseconds)
            if (isActive) {
                triggerTimeout(id)
            }
        }
        timeouts[id] = job
        return id.toDouble()
    }

    fun setInterval(delay: Double): Double {
        val id = getNextTimeoutID()
        val job = scope.launch {
            while (isActive) {
                delay(delay.milliseconds)
                if (isActive) {
                    triggerInterval(id)
                }
            }
        }
        timeouts[id] = job
        return id.toDouble()
    }

    fun clearTimeout(id: Int) {
        timeouts.remove(id)?.cancel("Cleared by clearTimeout")
    }

    fun clearInterval(id: Int) {
        timeouts.remove(id)?.cancel("Cleared by clearInterval")
    }

    private fun getNextTimeoutID(): Int {
        return if (timeoutIDs.hasNext()) {
            timeoutIDs.next()
        } else {
            timeoutIDs = (0..Int.MAX_VALUE).iterator()
            timeoutIDs.next()
        }
    }

    override fun close() {
        Logger.d("Closing JSTimeout, cancelling all timeouts")
        scope.cancel("JSTimeout closed")
        timeouts.clear()
    }
}