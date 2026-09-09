package com.fajr.callcompanion.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.*
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import com.fajr.callcompanion.model.Contact
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class FajrCallService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var contactsQueue = mutableListOf<Contact>()
    private var currentContactIndex = 0
    private val isCallInProgress = AtomicBoolean(false)
    private var isStoppedByUser = false

    private lateinit var telephonyManager: TelephonyManager
    private var phoneStateListener: PhoneStateListener? = null

    companion object {
        const val CHANNEL_ID = "FajrCallServiceChannel"
        const val ACTION_START = "ACTION_START_CALLS"
        const val ACTION_STOP = "ACTION_STOP_CALLS"

        var isRunning = false
        var currentStatusMessage = "Idle"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        setupPhoneStateListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val notification = buildNotification("Preparing Fajr calls...")
                startForeground(1001, notification)
                isRunning = true
                isStoppedByUser = false
                
                contactsQueue = mutableListOf(
                    Contact("1", "Friend 1", "+1234567890", 25),
                    Contact("2", "Friend 2", "+0987654321", 20)
                )
                currentContactIndex = 0
                processNextCall()
            }
            ACTION_STOP -> {
                stopCallingSequence("Stopped by grandfather")
            }
        }
        return START_NOT_STICKY
    }

    private fun processNextCall() {
        if (isStoppedByUser) return

        if (currentContactIndex >= contactsQueue.size) {
            stopCallingSequence("All Fajr calls completed!")
            return
        }

        val contact = contactsQueue[currentContactIndex]
        currentStatusMessage = "Calling ${contact.name}..."
        updateNotification(currentStatusMessage)

        makeSimCall(contact.phoneNumber)

        serviceScope.launch {
            delay(contact.ringDurationSeconds * 1000L)
            if (isCallInProgress.get()) {
                currentStatusMessage = "No answer from ${contact.name}. Moving next..."
                updateNotification(currentStatusMessage)
                endCurrentCall()
                delay(5000L)
                currentContactIndex++
                processNextCall()
            }
        }
    }

    private fun makeSimCall(phoneNumber: String) {
        try {
            isCallInProgress.set(true)
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$phoneNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(callIntent)
        } catch (e: SecurityException) {
            stopCallingSequence("Permission error making call")
        }
    }

    private fun endCurrentCall() {
        isCallInProgress.set(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager
            try {
                telecomManager.endCall()
            } catch (e: SecurityException) {
            }
        }
    }

    private fun setupPhoneStateListener() {
        phoneStateListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                when (state) {
                    TelephonyManager.CALL_STATE_IDLE -> {
                        if (isCallInProgress.getAndSet(false)) {
                            serviceScope.launch {
                                delay(4000L)
                                currentContactIndex++
                                processNextCall()
                            }
                        }
                    }
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        currentStatusMessage = "In call with ${contactsQueue.getOrNull(currentContactIndex)?.name ?: "Friend"}"
                        updateNotification(currentStatusMessage)
                    }
                }
            }
        }
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    private fun stopCallingSequence(reason: String) {
        isStoppedByUser = true
        isRunning = false
        currentStatusMessage = reason
        updateNotification(reason)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Fajr Call Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val stopIntent = Intent(this, FajrCallService::class.java).apply { action = ACTION_STOP }
        val stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Fajr Call Companion")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .addAction(android.R.drawable.ic_delete, "STOP ALL CALLS", stopPendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = buildNotification(text)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1001, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        phoneStateListener?.let { telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE) }
        serviceScope.cancel()
        isRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
