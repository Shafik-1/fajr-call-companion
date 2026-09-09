# Fajr Call Companion (مساعد صلاة الفجر)

An automated, senior-friendly Android application built with Kotlin and Jetpack Compose to help elders automatically call their list of friends one-by-one over SIM card for Fajr prayer without needing manual interaction.

---

## 📱 Features

1. **Senior-Accessible UI (High Contrast)**:
   - Giant high-visibility action buttons (**START FAJR CALLS**, **RESUME CALLS**, **STOP CALLS**).
   - Live status card showing remaining cooldown seconds and who is being called next.

2. **Full Bilingual Localization (English & العربية)**:
   - Persistent language selection toggle (**English** / **العربية**) in Settings.
   - Instantly localizes titles, lists, status messages, and navigation options across the entire app.
   - Remembers language choice across app restarts via `SharedPreferences`.

3. **Autonomous Call Automation Engine**:
   - Dials contacts sequentially on the phone's native SIM card.
   - Monitors call state (`IDLE`, `OFFHOOK`, `RINGING`).
   - Automatically hangs up after a configurable ring duration (e.g. 25s) if unanswered.
   - Pauses for a configurable cooldown delay (e.g. 5s) before dialing the next friend.
   - Preserves state: allows resuming from the last stopped index or restarting from contact #1.

4. **Active Queue Incoming Call Interceptor**:
   - Monitors incoming calls while the Fajr queue sequence is active.
   - Checks if the caller is in the active Fajr contact list.
   - **Action**: Instantly declines the call (`call.reject()` / `call.disconnect()`).
   - **Queue Prioritization**:
     - If the caller has **not been called yet**: Moves them directly to the top of the remaining queue (`currentContactIndex + 1`) to call them immediately after the declination.
     - If the caller was **already called**: Declines without re-calling and resumes normal queue order.

5. **Contact Management & Backup**:
   - System contact picker with instant search by name or number.
   - Import & Export contact list backups via CSV files (`fajr_contacts_backup.csv`).

---

## 🛠 Tech Stack & Architecture

- **Language**: Kotlin 1.9
- **UI Framework**: Jetpack Compose (Material3 Design System)
- **Background Engine**: Android Foreground Service (`FajrCallService`)
- **Call Screening & Control**: Android Telecom Companion Service (`InCallService`, `RoleManager.ROLE_CALL_COMPANION`)
- **Persistence**: Android `SharedPreferences`
- **CI/CD Pipeline**: GitHub Actions (`build.yml`) compiled via Gradle 8.4 & JDK 17

---

## ⚙️ Core Logic & Key Methods

### 1. Main Navigation & Preferences (`MainActivity.kt`)
- `AppNavigation()`: Manages screen switching (`HOME`, `CONTACT_PICKER`, `SETTINGS`) and language state (`EN` / `AR`).
- `FajrHomeScreen()`: Displays big high-contrast controls and real-time call queue progress.
- `SettingsScreen()`: Configures ring duration, cooldown delay, language toggle, and CSV backup import/export.

### 2. Autonomous Service Engine (`FajrCallService.kt`)
- `startCallingSequence()`: Initializes `contactsQueue`, resets/loads indices, acquires `WakeLock`, and starts foreground notification.
- `processNextCall()`: Evaluates queue index and initiates `makeSimCall(phoneNumber)`.
- `makeSimCall(phoneNumber)`: Launches native SIM dialer `Intent.ACTION_CALL` with dual-SIM bypass flags (`simSlot`, `com.android.phone.extra.slot`).
- `endCurrentCall()`: Triggers multi-strategy auto-hangup:
  - **Strategy 1**: Native `InCallService.disconnectActiveCall()` via Telecom Companion binding.
  - **Strategy 2**: `TelecomManager.endCall()` (Android 9+).
  - **Strategy 3**: Reflection on `ITelephony.endCall()`.
  - **Strategy 4**: Media key broadcast (`KEYCODE_HEADSETHOOK`).
- `handleIncomingQueueContact(phoneNumber)`:
  - Compares incoming caller against `contactsQueue`.
  - Re-orders queue dynamically if caller is uncalled (`queue.add(currentContactIndex + 1, caller)`).

### 3. Native InCall Companion Interceptor (`FajrInCallService.kt`)
- `onCallAdded(call)`: Binds active Android Telecom call objects.
- `onCallRemoved(call)`: Clears call object references.
- `disconnectActiveCall()`: Invokes native `call.disconnect()` on active calls.
- **Incoming Interceptor Hook**: Rejects incoming calls matching active Fajr contacts via `call.reject(false, null)`.

---

## 📶 Wireless ADB Debugging & Deployment Guide

This project is configured for automated wireless deployment directly to physical test devices (e.g. Redmi Note 8 Pro / Xiaomi MIUI).

### 1. Enable Wireless ADB on Device
1. Go to **Settings -> Developer Options**.
2. Enable **USB Debugging** and **Wireless Debugging**.
3. Note the IP address and Port (e.g. `192.168.1.205:44999`).

### 2. Connect Device over WiFi
```bash
adb connect 192.168.1.205:44999
adb devices
```

### 3. Wireless Clean Reinstall Command
```bash
# Download compiled APK from GitHub Actions artifact
curl -sL https://nightly.link/Shafik-1/fajr-call-companion/workflows/build.yml/main/FajrCallCompanion-APK.zip -o fajr_app.zip
unzip -o fajr_app.zip

# Clean uninstall and stream install over WiFi
adb -s 192.168.1.205:44999 uninstall com.fajr.callcompanion
adb -s 192.168.1.205:44999 install app-debug.apk

# Launch MainActivity on device
adb -s 192.168.1.205:44999 shell am start -n com.fajr.callcompanion/.MainActivity
```
