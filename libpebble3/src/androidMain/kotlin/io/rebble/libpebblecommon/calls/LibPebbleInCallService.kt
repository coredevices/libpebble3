package io.rebble.libpebblecommon.calls

import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.ContactsContract.Contacts
import android.telecom.Call
import android.telecom.InCallService
import android.telecom.VideoProfile
import co.touchlab.kermit.Logger
import io.rebble.libpebblecommon.connection.LibPebble
import io.rebble.libpebblecommon.di.LibPebbleKoinComponent
import org.koin.core.component.inject
import kotlin.random.Random
import kotlin.random.nextUInt

class LibPebbleInCallService : InCallService(), LibPebbleKoinComponent {
    companion object {
        private val logger = Logger.withTag("LibPebbleInCallService")
    }

    private val libPebble: LibPebble by inject()

    override fun onCreate() {
        super.onCreate()
        logger.d { "onCreate()" }
        libPebble.currentCall.value = null
    }

    override fun onDestroy() {
        logger.d { "onDestroy()" }
        libPebble.currentCall.value = null
        super.onDestroy()
    }

    private inner class Callback(private val cookie: UInt) : Call.Callback() {
        override fun onDetailsChanged(
            call: Call?,
            details: Call.Details?,
        ) {
            logger.d { "Call details changed: $details / ${details?.extras?.dump()}" }
        }

        override fun onStateChanged(call: Call?, state: Int) {
            call ?: return
            logger.d { "Call state changed: ${call.state} (arg state: $state)" }
            libPebble.currentCall.value = createLibPebbleCall(call, cookie)
        }
    }

    override fun onCallAdded(call: Call?) {
        call ?: return
        if (calls.size > 1) {
            logger.w { "Multiple calls detected" }
        }
        val cookie = Random.nextUInt()
        val callback = Callback(cookie)
        calls.filter { it != call }.forEach {
            it.unregisterCallback(callback)
        }
        logger.d { "New call in state: ${call.state}" }
        call.registerCallback(callback)
        libPebble.currentCall.value = createLibPebbleCall(call, cookie)
    }

    override fun onCallRemoved(call: Call?) {
        call ?: return
        libPebble.currentCall.value = null
    }

    private fun Call.resolveContactName(): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            details.contactDisplayName
        } else {
            val cursor = contentResolver.query(
                Contacts.CONTENT_URI,
                arrayOf(Contacts.DISPLAY_NAME),
                Contacts.HAS_PHONE_NUMBER + " = 1 AND " + ContactsContract.CommonDataKinds.Phone.NUMBER + " = ?",
                arrayOf(details.handle.schemeSpecificPart),
                null
            )
            val name = cursor?.use {
                if (it.moveToFirst()) {
                    it.getString(it.getColumnIndexOrThrow(Contacts.DISPLAY_NAME))
                } else {
                    null
                }
            }
            return name
        }
    }

    private fun Call.resolveContactNumber(): String {
        return this.details.handle?.schemeSpecificPart ?: "Unknown"
    }

    private fun createLibPebbleCall(call: Call, cookie: UInt): io.rebble.libpebblecommon.calls.Call? =
        when (call.state) {
            Call.STATE_RINGING -> io.rebble.libpebblecommon.calls.Call.RingingCall(
                contactName = call.resolveContactName(),
                contactNumber = call.resolveContactNumber(),
                onCallEnd = { call.disconnect() },
                onCallAnswer = { call.answer(VideoProfile.STATE_AUDIO_ONLY) },
                cookie = cookie,
            )
            Call.STATE_DIALING, Call.STATE_CONNECTING -> io.rebble.libpebblecommon.calls.Call.DialingCall(
                contactName = call.resolveContactName(),
                contactNumber = call.resolveContactNumber(),
                onCallEnd = { call.disconnect() },
                cookie = cookie,
            )
            Call.STATE_ACTIVE, Call.STATE_DISCONNECTING -> io.rebble.libpebblecommon.calls.Call.ActiveCall(
                contactName = call.resolveContactName(),
                contactNumber = call.resolveContactNumber(),
                onCallEnd = { call.disconnect() },
                cookie = cookie,
            )
            Call.STATE_HOLDING -> io.rebble.libpebblecommon.calls.Call.HoldingCall(
                contactName = call.resolveContactName(),
                contactNumber = call.resolveContactNumber(),
                onCallEnd = { call.disconnect() },
                cookie = cookie,
            )
            else -> {
                logger.w { "Unknown call state: ${call.state}" }
                null
            }
        }

    private fun Bundle.dump(): String {
        return keySet().joinToString(prefix = "\n", separator = "\n") {
            "$it = ${get(it)}"
        }
    }
}
