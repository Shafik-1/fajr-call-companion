package com.fajr.callcompanion.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.*
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import androidx.core.app.NotificationCompat
import com.fajr.callcompanion.model.ContactItem
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

class FajrCallService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private var contactsQueue = mutableListOf<ContactItem>()
    private var currentContactIndex = 0
    private val isCallInProgress = AtomicBoolean(false)
    private var isStoppedByUser = false
    private var ringDurationSeconds = 25
    private var delayBetweenCallsSeconds = 5
    private var activeJob: Job? = null

    private lateinit var telephonyManager: TelephonyManager
    private var phoneStateListener: PhoneStateListener? = null

    companion object {
        const val CHANNEL_ID = "FajrCallServiceChannel"
        const val ACTION_START = "ACTION_START_CALLS"
        const val ACTION_STOP = "ACTION_STOP_CALLS"
        const val ACTION_RESTART_INDEX = "ACTION_RESTART_INDEX"
        const val EXTRA_NAMES = "EXTRA_NAMES"
        const val EXTRA_NUMBERS = "EXTRA_NUMBERS"
        const val EXTRA_RING_DURATION = "EXTRA_RING_DURATION"
        const val EXTRA_DELAY_BETWEEN = "EXTRA_DELAY_BETWEEN"
        const val EXTRA_START_INDEX = "EXTRA_START_INDEX"

        const val PREFS_NAME = "fajr_service_prefs"
        const val KEY_LAST_INDEX = "last_stopped_index"

        var isRunning = false
        var currentStatusMessage = "Idle"
        var currentActiveIndex = 0
        var remainingSeconds = 0
        var isPausePhase = false
        var onStatusUpdated: ((String, Int, Int, Boolean) -> Unit)? = null
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
                val names = intent.getStringArrayExtra(EXTRA_NAMES) ?: emptyArray()
                val numbers = intent.getStringArrayExtra(EXTRA_NUMBERS) ?: emptyArray()
                ringDurationSeconds = intent.getIntExtra(EXTRA_RING_DURATION, 25)
                delayBetweenCallsSeconds = intent.getIntExtra(EXTRA_DELAY_BETWEEN, 5)
                val startIndex = intent.getIntExtra(EXTRA_START_INDEX, 0)

                contactsQueue.clear()
                for (i in names.indices) {
                    if (i < numbers.size) {
                        contactsQueue.add(ContactItem(id = i.toString(), name = names[i], phoneNumber = numbers[i], isSelected = true))
                    }
                }

                if (contactsQueue.isEmpty()) {
                    updateStatus("No contacts selected to call!", 0)
                    stopCallingSequence("No contacts selected to call!")
                    return START_NOT_STICKY
                }

                val notification = buildNotification("Preparing Fajr calls...")
                startForeground(1001, notification)
                isRunning = true
                isStoppedByUser = false
                currentContactIndex = if (startIndex < contactsQueue.size) startIndex else 0
                processNextCall()
            }
            ACTION_STOP -> {
                endCurrentCall()
                stopCallingSequence("Stopped by user at contact #${currentContactIndex + 1}")
            }
        }
        return START_NOT_STICKY
    }

    private fun processNextCall() {
        if (isStoppedByUser) return

        if (currentContactIndex >= contactsQueue.size) {
            saveLastStoppedIndex(0)
            updateStatus("All Fajr calls completed!", 0)
            stopCallingSequence("All Fajr calls completed!")
            return
        }

        saveLastStoppedIndex(currentContactIndex)
        val contact = contactsQueue[currentContactIndex]
        val statusText = "Calling ${contact.name} (${currentContactIndex + 1}/${contactsQueue.size})..."
        updateStatus(statusText, currentContactIndex)
        updateNotification(statusText)

        makeSimCall(contact.phoneNumber)

        activeJob?.cancel()
        activeJob = serviceScope.launch {
            isPausePhase = false
            for (sec in ringDurationSeconds downTo 1) {
                if (!isCallInProgress.get() || isStoppedByUser) break
                remainingSeconds = sec
                updateStatus("Ringing ${contact.name} (${currentContactIndex + 1}/${contactsQueue.size})", currentContactIndex, sec, false)
                delay(1000L)
            }

            if (isCallInProgress.get() && !isStoppedByUser) {
                val noAnsText = "No answer from ${contact.name}. Hanging up..."
                updateStatus(noAnsText, currentContactIndex, 0, false)
                endCurrentCall()
            }
        }
    }

    private fun makeSimCall(phoneNumber: String) {
        try {
            isCallInProgress.set(true)
            val formattedNumber = phoneNumber.replace(" ", "").replace("-", "")
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:$formattedNumber")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(callIntent)
        } catch (e: SecurityException) {
            updateStatus("Permission error making call", currentContactIndex)
            stopCallingSequence("Permission error making call")
        }
    }

    private fun endCurrentCall() {
        isCallInProgress.set(false)
        activeJob?.cancel()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val telecomManager = getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    telecomManager.endCall()
                    return
                }
            }
        } catch (e: Exception) {
            // Fallback for custom ROMs / MIUI
        }

        try {
            val telephonyClass = Class.forName(telephonyManager.javaClass.name)
            val methodGetITelephony = telephonyClass.getDeclaredMethod("getITelephony")
            methodGetITelephony.isAccessible = true
            val iTelephony = methodGetITelephony.invoke(telephonyManager)
            val methodEndCall = iTelephony.javaClass.getDeclaredMethod("endCall")
            methodEndCall.invoke(iTelephony)
        } catch (e: Exception) {
            // Log fallback fail
        }
    }

    private fun setupPhoneStateListener() {
        phoneStateListener = object : PhoneStateListener() {
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                when (state) {
                    TelephonyManager.CALL_STATE_IDLE -> {
                        val wasInCall = isCallInProgress.getAndSet(false)
                        if (!isStoppedByUser && (wasInCall || isRunning)) {
                            activeJob?.cancel()
                            // Mark current contact as done by advancing index!
                            currentContactIndex++
                            saveLastStoppedIndex(currentContactIndex)

                            activeJob = serviceScope.launch {
                                isPausePhase = true
                                for (sec in delayBetweenCallsSeconds downTo 1) {
                                    if (isStoppedByUser) break
                                    remainingSeconds = sec
                                    updateStatus("Call completed! Pause before next call (${sec}s)...", currentContactIndex, sec, true)
                                    delay(1000L)
                                }
                                if (!isStoppedByUser) {
                                    processNextCall()
                                }
                            }
                        }
                    }
                    TelephonyManager.CALL_STATE_OFFHOOK -> {
                        if (!isStoppedByUser) {
                            val inCallText = "In call with ${contactsQueue.getOrNull(currentContactIndex)?.name ?: "Friend"}"
                            updateStatus(inCallText, currentContactIndex, 0, false)
                            updateNotification(inCallText)
                        }
                    }
                }
            }
        }
        telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE)
    }

    private fun stopCallingSequence(reason: String) {
        isStoppedByUser = true
        isRunning = false
        isCallInProgress.set(false)
        activeJob?.cancel()
        updateStatus(reason, currentContactIndex)
        updateNotification(reason)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateStatus(message: String, index: Int, countdown: Int = 0, isPause: Boolean = false) {
        currentStatusMessage = message
        currentActiveIndex = index
        remainingSeconds = countdown
        isPausePhase = isPause
        onStatusUpdated?.invoke(message, index, countdown, isPause)
    }

    private fun saveLastStoppedIndex(index: Int) {
        val sp = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sp.edit().putInt(KEY_LAST_INDEX, index).apply()
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
