package com.fajr.callcompanion.service

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button as AndroidButton
import android.widget.LinearLayout
import android.widget.TextView as AndroidTextView
import androidx.core.app.ActivityCompat
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
    private var selectedSimSlot = 0
    private var enableInterceptor = true
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
        const val EXTRA_SIM_SLOT = "EXTRA_SIM_SLOT"
        const val EXTRA_ENABLE_INTERCEPTOR = "EXTRA_ENABLE_INTERCEPTOR"

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
                selectedSimSlot = intent.getIntExtra(EXTRA_SIM_SLOT, 0)
                enableInterceptor = intent.getBooleanExtra(EXTRA_ENABLE_INTERCEPTOR, true)
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
                putExtra("simSlot", selectedSimSlot)
                putExtra("com.android.phone.extra.slot", selectedSimSlot)
                putExtra("subscription", selectedSimSlot)
                putExtra("sim_slot", selectedSimSlot)
            }
            startActivity(callIntent)
        } catch (e: SecurityException) {
            updateStatus("Permission error making call", currentContactIndex)
            stopCallingSequence("Permission error making call")
        }
    }

    private fun endCurrentCall() {
        android.util.Log.d("FajrCall", ">>> endCurrentCall triggered <<<")
        isCallInProgress.set(false)
        activeJob?.cancel()

        // Strategy 1: Native InCallService disconnect
        if (FajrInCallService.disconnectActiveCall()) {
            android.util.Log.d("FajrCall", "Strategy 1 (InCallService) SUCCESS!")
            return
        } else {
            android.util.Log.d("FajrCall", "Strategy 1 (InCallService) skipped or null call")
        }

        // Strategy 2: TelecomManager
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val telecomManager = getSystemService(Context.TELECOM_SERVICE) as android.telecom.TelecomManager
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
                    val res = telecomManager.endCall()
                    android.util.Log.d("FajrCall", "Strategy 2 (TelecomManager.endCall) result: $res")
                } else {
                    android.util.Log.d("FajrCall", "Strategy 2 missing ANSWER_PHONE_CALLS permission")
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("FajrCall", "Strategy 2 exception: ${e.message}")
        }

        // Strategy 3: ITelephony Reflection
        try {
            val telephonyClass = Class.forName(telephonyManager.javaClass.name)
            val methodGetITelephony = telephonyClass.getDeclaredMethod("getITelephony")
            methodGetITelephony.isAccessible = true
            val iTelephony = methodGetITelephony.invoke(telephonyManager)
            val methodEndCall = iTelephony.javaClass.getDeclaredMethod("endCall")
            val res = methodEndCall.invoke(iTelephony)
            android.util.Log.d("FajrCall", "Strategy 3 (ITelephony.endCall) result: $res")
        } catch (e: Exception) {
            android.util.Log.e("FajrCall", "Strategy 3 exception: ${e.message}")
        }

        // Strategy 4: Media Key Broadcast
        try {
            val mediaKeyIntent = Intent(Intent.ACTION_MEDIA_BUTTON)
            mediaKeyIntent.putExtra(Intent.EXTRA_KEY_EVENT, android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_HEADSETHOOK))
            sendOrderedBroadcast(mediaKeyIntent, null)
            val mediaKeyUpIntent = Intent(Intent.ACTION_MEDIA_BUTTON)
            mediaKeyUpIntent.putExtra(Intent.EXTRA_KEY_EVENT, android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_HEADSETHOOK))
            sendOrderedBroadcast(mediaKeyUpIntent, null)
            android.util.Log.d("FajrCall", "Strategy 4 (MediaKey broadcast) sent")
        } catch (e: Exception) {
            android.util.Log.e("FajrCall", "Strategy 4 exception: ${e.message}")
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

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var overlayText: AndroidTextView? = null
    private var overlaySubText: AndroidTextView? = null

    private fun showOverlayWindow() {
        if (!Settings.canDrawOverlays(this)) return
        if (overlayView != null) return

        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 100
            }

            val layout = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 30, 40, 30)
                background = GradientDrawable().apply {
                    setColor(AndroidColor.parseColor("#0F172A"))
                    cornerRadius = 32f
                }
                elevation = 20f
            }

            val titleView = AndroidTextView(this).apply {
                text = "FAJR CALL COMPANION"
                textSize = 12f
                setTextColor(AndroidColor.parseColor("#94A3B8"))
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
            layout.addView(titleView)

            overlayText = AndroidTextView(this).apply {
                text = "Calling..."
                textSize = 26f
                setTextColor(AndroidColor.WHITE)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
            layout.addView(overlayText)

            overlaySubText = AndroidTextView(this).apply {
                text = "0s remaining"
                textSize = 18f
                setTextColor(AndroidColor.parseColor("#38BDF8"))
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
            }
            layout.addView(overlaySubText)

            val stopBtn = AndroidButton(this).apply {
                text = "STOP FAJR CALLS"
                setTextColor(AndroidColor.WHITE)
                background = GradientDrawable().apply {
                    setColor(AndroidColor.parseColor("#DC2626"))
                    cornerRadius = 20f
                }
                setOnClickListener {
                    endCurrentCall()
                    stopCallingSequence("Stopped by user via overlay")
                }
            }
            val btnParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 16
            }
            layout.addView(stopBtn, btnParams)

            overlayView = layout
            windowManager?.addView(overlayView, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateOverlayContent(status: String, seconds: Int) {
        if (overlayView == null) showOverlayWindow()
        overlayText?.text = status
        overlaySubText?.text = if (seconds > 0) "${seconds}s Remaining" else ""
    }

    private fun removeOverlayWindow() {
        try {
            if (overlayView != null) {
                windowManager?.removeView(overlayView)
                overlayView = null
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateStatus(message: String, index: Int, countdown: Int = 0, isPause: Boolean = false) {
        currentStatusMessage = message
        currentActiveIndex = index
        remainingSeconds = countdown
        isPausePhase = isPause
        updateOverlayContent(message, countdown)
        onStatusUpdated?.invoke(message, index, countdown, isPause)
    }

    private fun stopCallingSequence(reason: String) {
        isStoppedByUser = true
        isRunning = false
        isCallInProgress.set(false)
        activeJob?.cancel()
        removeOverlayWindow()
        updateStatus(reason, currentContactIndex)
        updateNotification(reason)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
        removeOverlayWindow()
        phoneStateListener?.let { telephonyManager.listen(it, PhoneStateListener.LISTEN_NONE) }
        serviceScope.cancel()
        isRunning = false
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
