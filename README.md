# Fajr Call Companion (مساعد صلاة الفجر)

An automated, senior-friendly Android application built with Kotlin and Jetpack Compose to help elders automatically call their list of friends one-by-one over SIM card for Fajr prayer without needing manual interaction.

---

## 📱 Features

1. **Senior-Accessible UI (High Contrast)**:
   - Giant high-visibility action buttons (**START FAJR CALLS**, **RESUME CALLS**, **RESTART #1**, **STOP CALLS**).
   - Live status card showing remaining countdown seconds and who is being called next.
   - High-contrast floating system overlay window over other apps for emergency stop control.

2. **Full Bilingual Localization (English & العربية)**:
   - Persistent language selection toggle (**English** / **العربية**) in Settings.
   - Instantly localizes titles, lists, status messages, and navigation options across the entire app.
   - Remembers language choice across app restarts via `SharedPreferences`.

3. **Autonomous Call Automation Engine**:
   - Dials contacts sequentially on the phone's native SIM card.
   - Dual-SIM support with slot configuration (`selectedSimSlot` 0 / 1).
   - Monitors call state (`IDLE`, `OFFHOOK`, `RINGING`).
   - Automatically hangs up after a configurable ring duration (e.g. 25s) if unanswered.
   - Pauses for a configurable cooldown delay (e.g. 5s) before dialing the next friend.
   - Preserves state: allows resuming from the last stopped index or restarting from contact #1.

4. **Active Queue Incoming Call Interceptor**:
   - Configurable interceptor toggle (`enableInterceptor`).
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
- **Persistence**: Android `SharedPreferences` (`fajr_prefs`, `fajr_service_prefs`)
- **CI/CD Pipeline**: GitHub Actions (`build.yml`) compiled via Gradle 8.4 & JDK 17

---

## ⚙️ Core Logic & Key Methods

### 1. Main Navigation & Preferences (`MainActivity.kt`)
- `AppNavigation(onStartCalls: (List<ContactItem>, Int, Int, Int, Int, Boolean) -> Unit, onStopCalls: () -> Unit)`: Manages screen switching (`HOME`, `CONTACT_PICKER`, `SETTINGS`) and language state (`EN` / `AR`).
- `startFajrCalls(contacts, ringDuration, delayBetween, startIndex, simSlot, enableInterceptor)`: Prepares service intent and launches `FajrCallService` as a foreground service.
- `FajrHomeScreen(...)`: Displays big high-contrast controls, real-time call queue progress, and action callbacks (`onStart`, `onRestart`, `onStop`).
- `SettingsScreen(...)`: Configures ring duration, cooldown delay, dual-SIM slot selection, incoming call interceptor toggle, language selection, and CSV backup import/export.

### 2. Autonomous Service Engine (`FajrCallService.kt`)
- `onStartCommand(intent, flags, startId)`: Receives parameters (`EXTRA_RING_DURATION`, `EXTRA_DELAY_BETWEEN`, `EXTRA_START_INDEX`, `EXTRA_SIM_SLOT`, `EXTRA_ENABLE_INTERCEPTOR`), initializes queue, and starts foreground notification.
- `processNextCall()`: Evaluates queue index and initiates `makeSimCall(phoneNumber)`.
- `makeSimCall(phoneNumber)`: Launches native SIM dialer `Intent.ACTION_CALL` with dual-SIM bypass flags (`simSlot`, `com.android.phone.extra.slot`, `subscription`).
- `endCurrentCall()`: Triggers multi-strategy auto-hangup:
  - **Strategy 1**: Native `InCallService.disconnectActiveCall()` via Telecom Companion binding.
  - **Strategy 2**: `TelecomManager.endCall()` (Android 9+).
  - **Strategy 3**: Reflection on `ITelephony.endCall()`.
  - **Strategy 4**: Media key broadcast (`KEYCODE_HEADSETHOOK`).

### 3. Native InCall Companion Interceptor (`FajrInCallService.kt`)
- `onCallAdded(call)`: Binds active Android Telecom call objects.
- `onCallRemoved(call)`: Clears call object references.
- `disconnectActiveCall()`: Invokes native `call.disconnect()` on active calls.

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

---

## 🤖 AI Agent Handover & Tooling Documentation

This codebase is maintained with the assistance of **Antigravity AI Agent**. This section documents the tools, access mechanisms, workflows, and automated procedures used by the AI agent to maintain, debug, and deploy this project.

### 1. Agent Toolset & Capabilities

| Tool Name | Type / Purpose | Usage & Context |
| :--- | :--- | :--- |
| `run_command` | Shell Execution | Executes Linux `bash` shell commands, `git` operations, `curl`, `adb`, and background task monitoring. |
| `view_file` | File Inspection | Reads codebase files with exact line numbers and byte ranges (e.g. `MainActivity.kt`, `FajrCallService.kt`). |
| `replace_file_content` | Code Editing | Performs precise single contiguous code modifications with target line boundaries and validation. |
| `multi_replace_file_content` | Batch Code Editing | Performs non-contiguous multi-chunk edits across a single source file in one turn. |
| `write_to_file` | File Creation | Creates new project files, scratch scripts, or documentation markdown files. |
| `grep_search` | Code Search | Fast `ripgrep` search across the workspace for symbols, strings, and method references. |
| `list_dir` | Directory Analysis | Lists project directories, folder trees, and build output contents. |
| `schedule` | Timer / Async Watcher | Schedules one-shot background timers to monitor GitHub Actions build jobs without blocking execution. |

### 2. Git & Version Control Operations

The agent interacts directly with Git via shell invocations inside the local workspace repository directory (`/mnt/Dspace/gedo/project call`):

- **Commit Verification**: Evaluates syntax and code structure before making git commits.
- **Atomic Commits**: Stages target files with `git add` and commits with structured, descriptive commit messages.
- **Remote Synchronization**: Pushes clean commits directly to `origin main` using `git push origin main`.
- **Clean History Maintenance**: In case of CI build failures, performs `git reset --hard <commit-sha>` or `git revert` followed by `git push origin main --force` to prevent commit clutter.

### 3. CI/CD & Build Monitoring Workflow

- **GitHub Actions Integration**: Pushes trigger automated cloud builds defined in `.github/workflows/build.yml`.
- **Status Checking**: Polls the GitHub REST API (`https://api.github.com/repos/Shafik-1/fajr-call-companion/actions/runs`) to monitor build states (`queued` -> `in_progress` -> `completed` / `success`).
- **Artifact Pipeline**: Uses `nightly.link` (`https://nightly.link/Shafik-1/fajr-call-companion/workflows/build.yml/main/FajrCallCompanion-APK.zip`) to fetch compiled APK artifacts automatically.

### 4. Wireless ADB Testing & UI Verification

- **Device Connection**: Maintains an active wireless ADB bridge to the test device at `192.168.1.205:44999`.
- **Streamed Installation**: Automates APK uninstallation and installation via `adb -s 192.168.1.205:44999 install -g app-debug.apk`.
- **Automated UI Testing & Screenshots**:
  - Launches activities: `adb shell am start -n com.fajr.callcompanion/.MainActivity`.
  - Simulates touch events: `adb shell input tap <x> <y>`.
  - Captures and inspects UI state visually: `adb shell screencap -p /sdcard/screen.png`.
  - Logs runtime diagnostic traces: `adb logcat -d -s FajrCall:V FajrInCall:V FajrInterceptor:V`.
