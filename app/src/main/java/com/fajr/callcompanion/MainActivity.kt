package com.fajr.callcompanion

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fajr.callcompanion.model.ContactItem
import com.fajr.callcompanion.service.FajrCallService
import java.io.InputStream
import java.io.OutputStream

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermissions()

        setContent {
            val customColorScheme = lightColorScheme(
                primary = Color(0xFF0284C7),
                onPrimary = Color.White,
                surface = Color.White,
                onSurface = Color(0xFF0F172A),
                background = Color(0xFFF4F6F8),
                onBackground = Color(0xFF1E293B)
            )

            MaterialTheme(colorScheme = customColorScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFFF4F6F8)
                ) {
                    AppNavigation(
                        onStartCalls = { selectedList, ringTime, delayTime, startIndex, simSlot, enableInterceptor ->
                            startFajrCalls(selectedList, ringTime, delayTime, startIndex, simSlot, enableInterceptor)
                        },
                        onStopCalls = { stopFajrCalls() }
                    )
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.ANSWER_PHONE_CALLS
        )
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        }

        if (!android.provider.Settings.canDrawOverlays(this)) {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(this, "Please grant 'Display over other apps' permission for call overlay", Toast.LENGTH_LONG).show()
        }
    }

    private fun startFajrCalls(contacts: List<ContactItem>, ringDuration: Int, delayBetween: Int, startIndex: Int, simSlot: Int, enableInterceptor: Boolean) {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            val intent = Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(this, "Please grant 'Display over other apps' permission for call overlay", Toast.LENGTH_LONG).show()
        }

        val names = contacts.map { it.name }.toTypedArray()
        val numbers = contacts.map { it.phoneNumber }.toTypedArray()

        val intent = Intent(this, FajrCallService::class.java).apply {
            action = FajrCallService.ACTION_START
            putExtra(FajrCallService.EXTRA_NAMES, names)
            putExtra(FajrCallService.EXTRA_NUMBERS, numbers)
            putExtra(FajrCallService.EXTRA_RING_DURATION, ringDuration)
            putExtra(FajrCallService.EXTRA_DELAY_BETWEEN, delayBetween)
            putExtra(FajrCallService.EXTRA_START_INDEX, startIndex)
            putExtra(FajrCallService.EXTRA_SIM_SLOT, simSlot)
            putExtra(FajrCallService.EXTRA_ENABLE_INTERCEPTOR, enableInterceptor)
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopFajrCalls() {
        val intent = Intent(this, FajrCallService::class.java).apply {
            action = FajrCallService.ACTION_STOP
        }
        startService(intent)
    }
}

enum class Screen { HOME, QUEUE_MANAGEMENT, CONTACT_PICKER, SETTINGS }
enum class AppLanguage { EN, AR }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(
    onStartCalls: (List<ContactItem>, Int, Int, Int, Int, Boolean) -> Unit,
    onStopCalls: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("fajr_prefs", Context.MODE_PRIVATE) }
    val servicePrefs = remember { context.getSharedPreferences(FajrCallService.PREFS_NAME, Context.MODE_PRIVATE) }

    var currentScreen by remember { mutableStateOf(Screen.HOME) }
    var selectedContacts by remember { mutableStateOf(loadSavedSelectedContacts(prefs)) }
    var ringDuration by remember { mutableIntStateOf(prefs.getInt("ring_duration", 25)) }
    var delayBetween by remember { mutableIntStateOf(prefs.getInt("delay_between", 5)) }
    var selectedSimSlot by remember { mutableIntStateOf(prefs.getInt("selected_sim_slot", -1)) }
    var enableInterceptor by remember { mutableStateOf(prefs.getBoolean("enable_interceptor", true)) }
    var currentLanguage by remember {
        mutableStateOf(if (prefs.getString("app_lang", "EN") == "AR") AppLanguage.AR else AppLanguage.EN)
    }

    var statusMessage by remember { mutableStateOf(FajrCallService.currentStatusMessage) }
    var activeIndex by remember { mutableIntStateOf(servicePrefs.getInt(FajrCallService.KEY_LAST_INDEX, 0)) }
    var remainingSec by remember { mutableIntStateOf(FajrCallService.remainingSeconds) }
    var isPause by remember { mutableStateOf(FajrCallService.isPausePhase) }

    DisposableEffect(Unit) {
        FajrCallService.onStatusUpdated = { msg, idx, countdown, pause ->
            statusMessage = msg
            activeIndex = idx
            remainingSec = countdown
            isPause = pause
        }
        onDispose {
            FajrCallService.onStatusUpdated = null
        }
    }

    when (currentScreen) {
        Screen.HOME -> FajrHomeScreen(
            selectedContacts = selectedContacts,
            statusMessage = statusMessage,
            stoppedIndex = activeIndex,
            remainingSec = remainingSec,
            isPausePhase = isPause,
            lang = currentLanguage,
            onOpenContacts = { currentScreen = Screen.CONTACT_PICKER },
            onOpenQueue = { currentScreen = Screen.QUEUE_MANAGEMENT },
            onOpenSettings = { currentScreen = Screen.SETTINGS },
            onStart = {
                if (selectedContacts.isEmpty()) {
                    val emptyMsg = if (currentLanguage == AppLanguage.AR) "القائمة فارغة! يرجى اختيار الأصدقاء أولاً" else "The contact list is empty! Please choose friends first."
                    Toast.makeText(context, emptyMsg, Toast.LENGTH_LONG).show()
                } else {
                    val savedIndex = servicePrefs.getInt(FajrCallService.KEY_LAST_INDEX, 0)
                    onStartCalls(selectedContacts, ringDuration, delayBetween, savedIndex, selectedSimSlot, enableInterceptor)
                }
            },
            onRestart = {
                servicePrefs.edit().putInt(FajrCallService.KEY_LAST_INDEX, 0).apply()
                activeIndex = 0
                val msg = if (currentLanguage == AppLanguage.AR) "تم إعادة تعيين القائمة إلى البداية!" else "Queue reset to beginning!"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            },
            onStop = {
                onStopCalls()
                statusMessage = if (currentLanguage == AppLanguage.AR) "تم الإيقاف بواسطة المستخدم" else "Stopped by user"
                remainingSec = 0
            }
        )

        Screen.QUEUE_MANAGEMENT -> QueueManagementScreen(
            contactsQueue = selectedContacts,
            activeIndex = activeIndex,
            isRunning = FajrCallService.isRunning,
            lang = currentLanguage,
            onBack = { currentScreen = Screen.HOME },
            onReorder = { newOrder ->
                selectedContacts = newOrder
                saveSelectedContacts(prefs, newOrder)
            }
        )

        Screen.CONTACT_PICKER -> ContactPickerScreen(
            alreadySelected = selectedContacts,
            lang = currentLanguage,
            onBack = { currentScreen = Screen.HOME },
            onSaveSelection = { newSelection ->
                selectedContacts = newSelection
                saveSelectedContacts(prefs, newSelection)
                currentScreen = Screen.HOME
            }
        )

        Screen.SETTINGS -> SettingsScreen(
            initialRingDuration = ringDuration,
            initialDelayBetween = delayBetween,
            initialLanguage = currentLanguage,
            initialEnableInterceptor = enableInterceptor,
            initialSimSlot = selectedSimSlot,
            selectedContacts = selectedContacts,
            onBack = { currentScreen = Screen.HOME },
            onSaveSettings = { newRing, newDelay, newLang, newInterceptor, newSimSlot ->
                ringDuration = newRing
                delayBetween = newDelay
                currentLanguage = newLang
                enableInterceptor = newInterceptor
                selectedSimSlot = newSimSlot
                prefs.edit()
                    .putInt("ring_duration", newRing)
                    .putInt("delay_between", newDelay)
                    .putString("app_lang", if (newLang == AppLanguage.AR) "AR" else "EN")
                    .putBoolean("enable_interceptor", newInterceptor)
                    .putInt("selected_sim_slot", newSimSlot)
                    .apply()
                currentScreen = Screen.HOME
            },
            onImportContacts = { importedList ->
                selectedContacts = importedList
                saveSelectedContacts(prefs, importedList)
                val msg = if (currentLanguage == AppLanguage.AR) "تم استيراد ${importedList.size} جهة اتصال!" else "Imported ${importedList.size} contacts!"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
fun FajrHomeScreen(
    selectedContacts: List<ContactItem>,
    statusMessage: String,
    stoppedIndex: Int,
    remainingSec: Int,
    isPausePhase: Boolean,
    lang: AppLanguage,
    onOpenContacts: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenSettings: () -> Unit,
    onStart: () -> Unit,
    onRestart: () -> Unit,
    onStop: () -> Unit
) {
    val selectedCount = selectedContacts.size
    val nextContact = selectedContacts.getOrNull(stoppedIndex)
    val isAr = lang == AppLanguage.AR

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFF4F6F8))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isAr) "مساعد صلاة الفجر" else "Fajr Call Companion",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1E293B)
            )
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .size(48.dp)
                    .background(Color.White, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = Color(0xFF475569),
                    modifier = Modifier.size(26.dp)
                )
            }
        }

        // Status & Countdown Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (remainingSec > 0 && isPausePhase) Color(0xFFFEF3C7) else Color.White,
                contentColor = Color(0xFF0F172A)
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (isAr) "الحالة الحالية" else "Current Status",
                    fontSize = 15.sp,
                    color = Color(0xFF64748B)
                )
                Spacer(modifier = Modifier.height(4.dp))
                val localizedStatus = when {
                    statusMessage.contains("Stopped by user via overlay") || statusMessage.contains("Stopped by user") -> if (isAr) "تم الإيقاف بواسطة المستخدم" else "Stopped by user"
                    statusMessage.contains("No contacts selected to call") -> if (isAr) "لم يتم تحديد أصدقاء للاتصال بهم!" else "No contacts selected to call!"
                    statusMessage.contains("All Fajr calls completed") -> if (isAr) "تمت جميع مكالمات الفجر بنجاح!" else "All Fajr calls completed!"
                    statusMessage.contains("Calling") -> {
                        if (isAr) statusMessage.replace("Calling", "جاري الاتصال بـ").replace("contact #", "صديق #") else statusMessage
                    }
                    statusMessage.contains("Ringing") -> {
                        if (isAr) statusMessage.replace("Ringing", "جاري الرنين لـ") else statusMessage
                    }
                    statusMessage.contains("In call with") -> {
                        if (isAr) statusMessage.replace("In call with", "في مكالمة مع") else statusMessage
                    }
                    statusMessage.contains("No answer from") -> {
                        if (isAr) statusMessage.replace("No answer from", "لا يوجد رد من").replace(". Hanging up...", ". جاري الإنهاء...") else statusMessage
                    }
                    statusMessage.contains("Call completed! Pause before next call") -> {
                        if (isAr) "تمت المكالمة! استراحة قبل المكالمة التالية..." else statusMessage
                    }
                    else -> statusMessage
                }
                Text(
                    text = localizedStatus,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0F172A)
                )
                if (remainingSec > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isAr) "$remainingSec ثانية" else "${remainingSec}s",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Black,
                        color = if (isPausePhase) Color(0xFFD97706) else Color(0xFF0284C7)
                    )
                    Text(
                        text = if (isPausePhase) (if (isAr) "العد التنازلي للاستراحة" else "PAUSE COUNTDOWN") else (if (isAr) "العد التنازلي للرنين" else "RING COUNTDOWN"),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF64748B)
                    )
                }
                if (stoppedIndex > 0 && selectedCount > 0 && remainingSec == 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isAr) "نقطة الاستئناف: صديق #${stoppedIndex + 1}${if (nextContact != null) " (${nextContact.name})" else ""}" else "Resume point: Contact #${stoppedIndex + 1}${if (nextContact != null) " (${nextContact.name})" else ""}",
                        fontSize = 13.sp,
                        color = Color(0xFF0284C7),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // CHOOSE LIST & VIEW QUEUE ROW
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = onOpenContacts,
                modifier = Modifier
                    .weight(1f)
                    .height(65.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7), contentColor = Color.White)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.List,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = if (isAr) "اختر القائمة" else "CHOOSE LIST",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = if (isAr) "$selectedCount أصدقاء" else "$selectedCount friends",
                            fontSize = 12.sp,
                            color = Color.White.copy(alpha = 0.95f)
                        )
                    }
                }
            }

            OutlinedButton(
                onClick = onOpenQueue,
                modifier = Modifier
                    .weight(1f)
                    .height(65.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF0284C7))
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.List,
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Column {
                        Text(
                            text = if (isAr) "ترتيب القائمة" else "VIEW QUEUE",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (isAr) "تعديل الدور" else "Reorder queue",
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // START / RESUME BUTTON
        Button(
            onClick = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(90.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A), contentColor = Color.White)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (stoppedIndex > 0)
                        (if (isAr) "استئناف المكالمات (#${stoppedIndex + 1})" else "RESUME CALLS (#${stoppedIndex + 1})")
                    else
                        (if (isAr) "ابدأ مكالمات الفجر" else "START FAJR CALLS"),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )
                if (nextContact != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (isAr) "المكالمة التالية: ${nextContact.name} (${nextContact.phoneNumber})"
                               else "Next call: ${nextContact.name} (${nextContact.phoneNumber})",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.92f)
                    )
                }
            }
        }

        // RESTART LIST & STOP CALLS BUTTON ROW
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // RESTART FROM BEGINNING BUTTON
            OutlinedButton(
                onClick = onRestart,
                modifier = Modifier
                    .weight(1f)
                    .height(65.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF0284C7))
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Refresh, contentDescription = "Restart", modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(if (isAr) "إعادة #1" else "Restart #1", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }

            // STOP ALL CALLS BUTTON
            Button(
                onClick = onStop,
                modifier = Modifier
                    .weight(1f)
                    .height(65.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626), contentColor = Color.White)
            ) {
                Text(
                    text = if (isAr) "إيقاف المكالمات" else "STOP CALLS",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactPickerScreen(
    alreadySelected: List<ContactItem>,
    lang: AppLanguage,
    onBack: () -> Unit,
    onSaveSelection: (List<ContactItem>) -> Unit
) {
    val context = LocalContext.current
    val isAr = lang == AppLanguage.AR
    var allContacts by remember { mutableStateOf(listOf<ContactItem>()) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedIds by remember {
        mutableStateOf(alreadySelected.map { it.phoneNumber }.toSet())
    }

    LaunchedEffect(Unit) {
        allContacts = fetchPhoneContacts(context)
    }

    val filteredContacts = remember(allContacts, searchQuery) {
        if (searchQuery.isBlank()) {
            allContacts
        } else {
            allContacts.filter {
                it.name.contains(searchQuery, ignoreCase = true) ||
                it.phoneNumber.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Scaffold(
        containerColor = Color(0xFFF4F6F8),
        topBar = {
            TopAppBar(
                title = { Text(if (isAr) "اختر الأصدقاء (${selectedIds.size})" else "Choose Friends (${selectedIds.size})", color = Color(0xFF0F172A), fontSize = 18.sp) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color(0xFF0F172A))
                    }
                },
                actions = {
                    TextButton(onClick = {
                        val selectedList = allContacts.filter { selectedIds.contains(it.phoneNumber) }
                        onSaveSelection(selectedList)
                    }) {
                        Text(if (isAr) "تم" else "DONE", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF16A34A))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF4F6F8))
                .padding(padding)
        ) {
            // Search Input Box
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                placeholder = { Text(if (isAr) "ابحث بالإسم أو الرقم..." else "Search by name or number...", color = Color(0xFF94A3B8)) },
                leadingIcon = {
                    Icon(Icons.Default.Search, contentDescription = "Search", tint = Color(0xFF64748B))
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear", tint = Color(0xFF64748B))
                        }
                    }
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.White,
                    unfocusedContainerColor = Color.White,
                    disabledContainerColor = Color.White,
                    focusedBorderColor = Color(0xFF0284C7),
                    unfocusedBorderColor = Color(0xFFCBD5E1),
                    focusedTextColor = Color(0xFF0F172A),
                    unfocusedTextColor = Color(0xFF0F172A)
                ),
                shape = RoundedCornerShape(12.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(
                    onClick = {
                        val filteredNumbers = filteredContacts.map { it.phoneNumber }
                        selectedIds = selectedIds + filteredNumbers
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFF0284C7))
                ) {
                    Text(if (isAr) "تحديد الكل" else "Select All", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                OutlinedButton(
                    onClick = {
                        val filteredNumbers = filteredContacts.map { it.phoneNumber }.toSet()
                        selectedIds = selectedIds - filteredNumbers
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFDC2626))
                ) {
                    Text(if (isAr) "إلغاء تحديد الكل" else "Deselect All", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }

            Divider(color = Color(0xFFCBD5E1))

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White)
            ) {
                items(filteredContacts) { contact ->
                    val isChecked = selectedIds.contains(contact.phoneNumber)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedIds = if (isChecked) {
                                    selectedIds - contact.phoneNumber
                                } else {
                                    selectedIds + contact.phoneNumber
                                }
                            }
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { checked ->
                                selectedIds = if (checked == true) {
                                    selectedIds + contact.phoneNumber
                                } else {
                                    selectedIds - contact.phoneNumber
                                }
                            },
                            colors = CheckboxDefaults.colors(
                                checkedColor = Color(0xFF16A34A),
                                uncheckedColor = Color(0xFF64748B)
                            )
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = contact.name,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF0F172A)
                            )
                            Text(
                                text = contact.phoneNumber,
                                fontSize = 14.sp,
                                color = Color(0xFF475569)
                            )
                        }
                    }
                    Divider(modifier = Modifier.padding(start = 48.dp), color = Color(0xFFE2E8F0))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    initialRingDuration: Int,
    initialDelayBetween: Int,
    initialLanguage: AppLanguage,
    initialEnableInterceptor: Boolean,
    initialSimSlot: Int,
    selectedContacts: List<ContactItem>,
    onBack: () -> Unit,
    onSaveSettings: (Int, Int, AppLanguage, Boolean, Int) -> Unit,
    onImportContacts: (List<ContactItem>) -> Unit
) {
    val context = LocalContext.current
    var ringDuration by remember { mutableIntStateOf(initialRingDuration) }
    var delayBetween by remember { mutableIntStateOf(initialDelayBetween) }
    var selectedLanguage by remember { mutableStateOf(initialLanguage) }
    var enableInterceptor by remember { mutableStateOf(initialEnableInterceptor) }
    var selectedSimSlot by remember { mutableIntStateOf(initialSimSlot) }
    val isAr = selectedLanguage == AppLanguage.AR

    // Export Launcher
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        uri?.let { exportContactsToCsv(context, it, selectedContacts) }
    }

    // Import Launcher
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            val list = importContactsFromCsv(context, it)
            if (list.isNotEmpty()) onImportContacts(list)
        }
    }

    Scaffold(
        containerColor = Color(0xFFF4F6F8),
        topBar = {
            TopAppBar(
                title = { Text(if (isAr) "إعدادات المكالمات" else "Call Settings", color = Color(0xFF0F172A), fontSize = 18.sp) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color(0xFF0F172A))
                    }
                },
                actions = {
                    IconButton(onClick = { onSaveSettings(ringDuration, delayBetween, selectedLanguage, enableInterceptor, selectedSimSlot) }) {
                        Icon(Icons.Default.Check, contentDescription = "Save", tint = Color(0xFF16A34A))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF4F6F8))
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Language Selection Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White, contentColor = Color(0xFF0F172A)),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (isAr) "لغة التطبيق / App Language" else "App Language / لغة التطبيق",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { selectedLanguage = AppLanguage.EN },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selectedLanguage == AppLanguage.EN) Color(0xFF0284C7) else Color(0xFFE2E8F0),
                                contentColor = if (selectedLanguage == AppLanguage.EN) Color.White else Color(0xFF475569)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("English", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                        Button(
                            onClick = { selectedLanguage = AppLanguage.AR },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selectedLanguage == AppLanguage.AR) Color(0xFF0284C7) else Color(0xFFE2E8F0),
                                contentColor = if (selectedLanguage == AppLanguage.AR) Color.White else Color(0xFF475569)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("العربية", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        }
                    }
                }
            }

            // Incoming Call Queue Interceptor Toggle Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White, contentColor = Color(0xFF0F172A)),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isAr) "اعتراض المكالمات الواردة وإعادة الترتيب" else "Decline & Prioritize Incoming Queue Calls",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0F172A)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isAr) "رفض المكالمة الواردة من الأصدقاء في القائمة والاتصال بهم فوراً" else "Decline calls from queue contacts & move uncalled friends to top of queue",
                            fontSize = 12.sp,
                            color = Color(0xFF64748B)
                        )
                    }
                    Switch(
                        checked = enableInterceptor,
                        onCheckedChange = { enableInterceptor = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Color(0xFF16A34A)
                        )
                    )
                }
            }

            // SIM Card Selection Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White, contentColor = Color(0xFF0F172A)),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (isAr) "شريحة الاتصال الافتراضية (SIM Card)" else "Default SIM Card for Calls",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = { selectedSimSlot = -1 },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selectedSimSlot == -1) Color(0xFF0284C7) else Color(0xFFE2E8F0),
                                contentColor = if (selectedSimSlot == -1) Color.White else Color(0xFF475569)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                        ) {
                            Text(if (isAr) "افتراضي النظام" else "Default / Ask", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        Button(
                            onClick = { selectedSimSlot = 0 },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selectedSimSlot == 0) Color(0xFF0284C7) else Color(0xFFE2E8F0),
                                contentColor = if (selectedSimSlot == 0) Color.White else Color(0xFF475569)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                        ) {
                            Text(if (isAr) "شريحة 1" else "SIM 1", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        Button(
                            onClick = { selectedSimSlot = 1 },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selectedSimSlot == 1) Color(0xFF0284C7) else Color(0xFFE2E8F0),
                                contentColor = if (selectedSimSlot == 1) Color.White else Color(0xFF475569)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 8.dp)
                        ) {
                            Text(if (isAr) "شريحة 2" else "SIM 2", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                    }
                }
            }
            // Ring Duration Config
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White, contentColor = Color(0xFF0F172A)),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (isAr) "مدة الرنين قبل الإنهاء" else "Ring Duration Before Hanging Up",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isAr) "$ringDuration ثانية" else "$ringDuration Seconds",
                        fontSize = 20.sp,
                        color = Color(0xFF0284C7),
                        fontWeight = FontWeight.ExtraBold
                    )
                    Slider(
                        value = ringDuration.toFloat(),
                        onValueChange = { ringDuration = it.toInt() },
                        valueRange = 3f..60f,
                        steps = 57,
                        colors = SliderDefaults.colors(thumbColor = Color(0xFF0284C7), activeTrackColor = Color(0xFF0284C7))
                    )
                }
            }

            // Delay Between Calls Config
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White, contentColor = Color(0xFF0F172A)),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = if (isAr) "مدة الاستراحة بين المكالمات" else "Pause Delay Between Calls",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isAr) "$delayBetween ثانية" else "$delayBetween Seconds",
                        fontSize = 20.sp,
                        color = Color(0xFF16A34A),
                        fontWeight = FontWeight.ExtraBold
                    )
                    Slider(
                        value = delayBetween.toFloat(),
                        onValueChange = { delayBetween = it.toInt() },
                        valueRange = 1f..30f,
                        steps = 29,
                        colors = SliderDefaults.colors(thumbColor = Color(0xFF16A34A), activeTrackColor = Color(0xFF16A34A))
                    )
                }
            }

            // Import & Export Backup Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White, contentColor = Color(0xFF0F172A)),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Backup & Share Contact List",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A)
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = { exportLauncher.launch("fajr_contacts_backup.csv") },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Export CSV", fontSize = 14.sp)
                        }
                        OutlinedButton(
                            onClick = { importLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.List, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Import CSV", fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QueueManagementScreen(
    contactsQueue: List<ContactItem>,
    activeIndex: Int,
    isRunning: Boolean,
    lang: AppLanguage,
    onBack: () -> Unit,
    onReorder: (List<ContactItem>) -> Unit
) {
    val isAr = lang == AppLanguage.AR
    var currentQueue by remember(contactsQueue) { mutableStateOf(contactsQueue) }

    Scaffold(
        containerColor = Color(0xFFF4F6F8),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isAr) "ترتيب قائمة الاتصال (${currentQueue.size})"
                        else "Call Queue Order (${currentQueue.size})",
                        color = Color(0xFF0F172A),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color(0xFF0F172A))
                    }
                }
            )
        }
    ) { padding ->
        if (currentQueue.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isAr) "القائمة فارغة! يرجى اختيار الأصدقاء أولاً" else "Queue is empty! Please choose friends first.",
                    fontSize = 16.sp,
                    color = Color(0xFF64748B),
                    fontWeight = FontWeight.SemiBold
                )
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                for (index in currentQueue.indices) {
                    val contact = currentQueue[index]
                    val isDone = isRunning && index < activeIndex
                    val isActive = isRunning && index == activeIndex

                    val cardBg = when {
                        isDone -> Color(0xFFF1F5F9)
                        isActive -> Color(0xFFE0F2FE)
                        else -> Color.White
                    }
                    val textColor = if (isDone) Color(0xFF94A3B8) else Color(0xFF0F172A)

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = cardBg),
                        elevation = CardDefaults.cardElevation(defaultElevation = if (isDone) 1.dp else 3.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = when {
                                        isDone -> Color(0xFFCBD5E1)
                                        isActive -> Color(0xFF0284C7)
                                        else -> Color(0xFF0284C7).copy(alpha = 0.15f)
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = "#${index + 1}",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isDone || isActive) Color.White else Color(0xFF0284C7)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column {
                                    Text(
                                        text = contact.name,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = textColor,
                                        textDecoration = if (isDone) TextDecoration.LineThrough else null
                                    )
                                    Text(
                                        text = contact.phoneNumber,
                                        fontSize = 13.sp,
                                        color = if (isDone) Color(0xFFCBD5E1) else Color(0xFF64748B)
                                    )
                                }
                            }

                            if (isDone) {
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = Color(0xFFDCFCE7),
                                    modifier = Modifier.padding(start = 4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = "Done",
                                            tint = Color(0xFF16A34A),
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (isAr) "تم" else "DONE",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF16A34A)
                                        )
                                    }
                                }
                            } else if (isActive) {
                                Surface(
                                    shape = RoundedCornerShape(20.dp),
                                    color = Color(0xFFBAE6FD),
                                    modifier = Modifier.padding(start = 4.dp)
                                ) {
                                    Text(
                                        text = if (isAr) "جاري الاتصال" else "CALLING",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF0369A1),
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            } else {
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    val canMoveUp = index > (if (isRunning) activeIndex + 1 else 0)
                                    IconButton(
                                        onClick = {
                                            if (canMoveUp) {
                                                val newList = currentQueue.toMutableList()
                                                val item = newList.removeAt(index)
                                                newList.add(index - 1, item)
                                                currentQueue = newList
                                                onReorder(newList)
                                            }
                                        },
                                        enabled = canMoveUp,
                                        modifier = Modifier
                                            .size(36.dp)
                                            .background(
                                                if (canMoveUp) Color(0xFFE2E8F0) else Color(0xFFF1F5F9),
                                                CircleShape
                                            )
                                    ) {
                                        Text(
                                            text = "▲",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (canMoveUp) Color(0xFF0F172A) else Color(0xFFCBD5E1)
                                        )
                                    }

                                    val canMoveDown = index < currentQueue.size - 1
                                    IconButton(
                                        onClick = {
                                            if (canMoveDown) {
                                                val newList = currentQueue.toMutableList()
                                                val item = newList.removeAt(index)
                                                newList.add(index + 1, item)
                                                currentQueue = newList
                                                onReorder(newList)
                                            }
                                        },
                                        enabled = canMoveDown,
                                        modifier = Modifier
                                            .size(36.dp)
                                            .background(
                                                if (canMoveDown) Color(0xFFE2E8F0) else Color(0xFFF1F5F9),
                                                CircleShape
                                            )
                                    ) {
                                        Text(
                                            text = "▼",
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (canMoveDown) Color(0xFF0F172A) else Color(0xFFCBD5E1)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

fun exportContactsToCsv(context: Context, uri: Uri, contacts: List<ContactItem>) {
    try {
        val outputStream: OutputStream? = context.contentResolver.openOutputStream(uri)
        outputStream?.bufferedWriter()?.use { writer ->
            writer.write("Name,PhoneNumber\n")
            for (c in contacts) {
                writer.write("\"${c.name}\",\"${c.phoneNumber}\"\n")
            }
        }
        Toast.makeText(context, "Exported successfully!", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}

fun importContactsFromCsv(context: Context, uri: Uri): List<ContactItem> {
    val list = mutableListOf<ContactItem>()
    try {
        val inputStream: InputStream? = context.contentResolver.openInputStream(uri)
        inputStream?.bufferedReader()?.useLines { lines ->
            lines.drop(1).forEach { line ->
                val parts = line.replace("\"", "").split(",")
                if (parts.size >= 2) {
                    list.add(ContactItem(id = parts[1], name = parts[0], phoneNumber = parts[1], isSelected = true))
                }
            }
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Import error: ${e.message}", Toast.LENGTH_SHORT).show()
    }
    return list
}

fun fetchPhoneContacts(context: Context): List<ContactItem> {
    val list = mutableListOf<ContactItem>()
    val cr: ContentResolver = context.contentResolver
    val cursor: Cursor? = cr.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        null, null, null,
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
    )
    cursor?.use {
        val nameIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        val numberIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
        val idIndex = it.getColumnIndex(ContactsContract.CommonDataKinds.Phone._ID)

        while (it.moveToNext()) {
            val name = if (nameIndex != -1) it.getString(nameIndex) ?: "Unknown" else "Unknown"
            val number = if (numberIndex != -1) it.getString(numberIndex) ?: "" else ""
            val id = if (idIndex != -1) it.getString(idIndex) ?: "" else ""

            if (number.isNotBlank()) {
                list.add(ContactItem(id = id, name = name, phoneNumber = number, isSelected = false))
            }
        }
    }
    return list.distinctBy { it.phoneNumber }
}

fun loadSavedSelectedContacts(prefs: SharedPreferences): List<ContactItem> {
    val raw = prefs.getString("selected_contacts_raw", "") ?: ""
    if (raw.isBlank()) return emptyList()

    val result = mutableListOf<ContactItem>()
    val pairs = raw.split(";")
    for (pair in pairs) {
        val parts = pair.split("|")
        if (parts.size >= 2) {
            result.add(ContactItem(id = parts[1], name = parts[0], phoneNumber = parts[1], isSelected = true))
        }
    }
    return result
}

fun saveSelectedContacts(prefs: SharedPreferences, contacts: List<ContactItem>) {
    val raw = contacts.joinToString(";") { "${it.name}|${it.phoneNumber}" }
    prefs.edit().putString("selected_contacts_raw", raw).apply()
}
