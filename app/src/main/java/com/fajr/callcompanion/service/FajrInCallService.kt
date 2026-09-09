package com.fajr.callcompanion.service

import android.telecom.Call
import android.telecom.InCallService

class FajrInCallService : InCallService() {

    companion object {
        var activeCall: Call? = null

        fun disconnectActiveCall(): Boolean {
            val call = activeCall
            if (call != null) {
                try {
                    call.disconnect()
                    return true
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            return false
        }
    }

    override fun onCallAdded(call: Call?) {
        super.onCallAdded(call)
        activeCall = call
        call?.registerCallback(object : Call.Callback() {
            override fun onStateChanged(c: Call?, state: Int) {
                super.onStateChanged(c, state)
                if (state == Call.STATE_DISCONNECTED) {
                    if (activeCall == c) activeCall = null
                }
            }
        })
    }

    override fun onCallRemoved(call: Call?) {
        super.onCallRemoved(call)
        if (activeCall == call) {
            activeCall = null
        }
    }
}
