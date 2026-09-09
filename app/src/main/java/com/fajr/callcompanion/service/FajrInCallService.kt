package com.fajr.callcompanion.service

import android.telecom.Call
import android.telecom.InCallService

class FajrInCallService : InCallService() {

    companion object {
        var activeCall: Call? = null

        fun disconnectActiveCall(): Boolean {
            val call = activeCall
            android.util.Log.d("FajrInCall", "disconnectActiveCall requested, call object: $call")
            if (call != null) {
                try {
                    call.disconnect()
                    android.util.Log.d("FajrInCall", "call.disconnect() executed successfully")
                    return true
                } catch (e: Exception) {
                    android.util.Log.e("FajrInCall", "call.disconnect() exception: ${e.message}")
                }
            }
            return false
        }
    }

    override fun onCallAdded(call: Call?) {
        super.onCallAdded(call)
        android.util.Log.d("FajrInCall", "onCallAdded triggered! Call: $call")
        activeCall = call
        call?.registerCallback(object : Call.Callback() {
            override fun onStateChanged(c: Call?, state: Int) {
                super.onStateChanged(c, state)
                android.util.Log.d("FajrInCall", "onStateChanged: $state")
                if (state == Call.STATE_DISCONNECTED) {
                    if (activeCall == c) activeCall = null
                }
            }
        })
    }

    override fun onCallRemoved(call: Call?) {
        super.onCallRemoved(call)
        android.util.Log.d("FajrInCall", "onCallRemoved triggered")
        if (activeCall == call) {
            activeCall = null
        }
    }
}
