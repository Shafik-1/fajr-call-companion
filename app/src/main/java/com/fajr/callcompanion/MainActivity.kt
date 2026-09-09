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
import androidx.compose.material.icons.filled.Search
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
                        onStartCalls = { selectedList, ringTime, delayTime, startIndex ->
                            startFajrCalls(selectedList, ringTime, delayTime, startIndex)
                        },
                        onStopCalls = { stopFajrCalls() }
                    )
                }
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.READ_CALL_LOG
        )
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 101)
        }
    }

    private fun startFajrCalls(contacts: List<ContactItem>, ringDuration: Int, delayBetween: Int, startIndex: Int) {
        val names = contacts.map { it.name }.toTypedArray()
        val numbers = contacts.map { it.phoneNumber }.toTypedArray()

        val intent = Intent(this, FajrCallService::class.java).apply {
            action = FajrCallService.ACTION_START
            putExtra(FajrCallService.EXTRA_NAMES, names)
            putExtra(FajrCallService.EXTRA_NUMBERS, numbers)
            putExtra(FajrCallService.EXTRA_RING_DURATION, ringDuration)
            putExtra(FajrCallService.EXTRA_DELAY_BETWEEN, delayBetween)
            putExtra(FajrCallService.EXTRA_START_INDEX, startIndex)
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

enum class Screen { HOME, CONTACT_PICKER, SETTINGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(
    onStartCalls: (List<ContactItem>, Int, Int, Int) -> Unit,
    onStopCalls: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("fajr_prefs", Context.MODE_PRIVATE) }
    val servicePrefs = remember { context.getSharedPreferences(FajrCallService.PREFS_NAME, Context.MODE_PRIVATE) }

    var currentScreen by remember { mutableStateOf(Screen.HOME) }
    var selectedContacts by remember { mutableStateOf(loadSavedSelectedContacts(prefs)) }
    var ringDuration by remember { mutableIntStateOf(prefs.getInt("ring_duration", 25)) }
    var delayBetween by remember { mutableIntStateOf(prefs.getInt("delay_between", 5)) }

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
            selectedCount = selectedContacts.size,
            statusMessage = statusMessage,
            stoppedIndex = activeIndex,
            remainingSec = remainingSec,
            isPausePhase = isPause,
            onOpenContacts = { currentScreen = Screen.CONTACT_PICKER },
            onOpenSettings = { currentScreen = Screen.SETTINGS },
            onStart = {
                val savedIndex = servicePrefs.getInt(FajrCallService.KEY_LAST_INDEX, 0)
                onStartCalls(selectedContacts, ringDuration, delayBetween, savedIndex)
            },
            onRestart = {
                servicePrefs.edit().putInt(FajrCallService.KEY_LAST_INDEX, 0).apply()
                activeIndex = 0
                onStartCalls(selectedContacts, ringDuration, delayBetween, 0)
            },
            onStop = {
                onStopCalls()
                statusMessage = "Stopped by user"
                remainingSec = 0
            }
        )

        Screen.CONTACT_PICKER -> ContactPickerScreen(
            alreadySelected = selectedContacts,
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
            selectedContacts = selectedContacts,
            onBack = { currentScreen = Screen.HOME },
            onSaveSettings = { newRing, newDelay ->
                ringDuration = newRing
                delayBetween = newDelay
                prefs.edit().putInt("ring_duration", newRing).putInt("delay_between", newDelay).apply()
                currentScreen = Screen.HOME
            },
            onImportContacts = { importedList ->
                selectedContacts = importedList
                saveSelectedContacts(prefs, importedList)
                Toast.makeText(context, "Imported ${importedList.size} contacts!", Toast.LENGTH_SHORT).show()
            }
        )
    }
}

@Composable
fun FajrHomeScreen(
    selectedCount: Int,
    statusMessage: String,
    stoppedIndex: Int,
    remainingSec: Int,
    isPausePhase: Boolean,
    onOpenContacts: () -> Unit,
    onOpenSettings: () -> Unit,
    onStart: () -> Unit,
    onRestart: () -> Unit,
    onStop: () -> Unit
) {
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
                text = "Fajr Call Companion",
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
                    text = "Current Status",
                    fontSize = 15.sp,
                    color = Color(0xFF64748B)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = statusMessage,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0F172A)
                )
                if (remainingSec > 0) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "${remainingSec}s",
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Black,
                        color = if (isPausePhase) Color(0xFFD97706) else Color(0xFF0284C7)
                    )
                    Text(
                        text = if (isPausePhase) "PAUSE COUNTDOWN" else "RING COUNTDOWN",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF64748B)
                    )
                }
                if (stoppedIndex > 0 && selectedCount > 0 && remainingSec == 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Resume point: Contact #${stoppedIndex + 1}",
                        fontSize = 13.sp,
                        color = Color(0xFF0284C7),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        // CHOOSE LIST BUTTON
        Button(
            onClick = onOpenContacts,
            modifier = Modifier
                .fillMaxWidth()
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
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = "CHOOSE LIST",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "$selectedCount friends saved",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.95f)
                    )
                }
            }
        }

        // START / RESUME BUTTON
        Button(
            onClick = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(85.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A), contentColor = Color.White)
        ) {
            Text(
                text = if (stoppedIndex > 0) "RESUME CALLS (#${stoppedIndex + 1})" else "START FAJR CALLS",
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )
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
                    Text("Restart #1", fontSize = 15.sp, fontWeight = FontWeight.Bold)
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
                    text = "STOP CALLS",
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
    onBack: () -> Unit,
    onSaveSelection: (List<ContactItem>) -> Unit
) {
    val context = LocalContext.current
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
                title = { Text("Choose Friends (${selectedIds.size})", color = Color(0xFF0F172A), fontSize = 18.sp) },
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
                        Text("DONE", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF16A34A))
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
                placeholder = { Text("Search by name or number...", color = Color(0xFF94A3B8)) },
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
                    Text("Select All", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                OutlinedButton(
                    onClick = {
                        val filteredNumbers = filteredContacts.map { it.phoneNumber }.toSet()
                        selectedIds = selectedIds - filteredNumbers
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFDC2626))
                ) {
                    Text("Deselect All", fontWeight = FontWeight.Bold, fontSize = 14.sp)
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
    selectedContacts: List<ContactItem>,
    onBack: () -> Unit,
    onSaveSettings: (Int, Int) -> Unit,
    onImportContacts: (List<ContactItem>) -> Unit
) {
    val context = LocalContext.current
    var ringDuration by remember { mutableIntStateOf(initialRingDuration) }
    var delayBetween by remember { mutableIntStateOf(initialDelayBetween) }

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
                title = { Text("Call Settings", color = Color(0xFF0F172A), fontSize = 18.sp) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color(0xFF0F172A))
                    }
                },
                actions = {
                    IconButton(onClick = { onSaveSettings(ringDuration, delayBetween) }) {
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
                .padding(16.dp)
        ) {
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
                        text = "Ring Duration Before Hanging Up",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "$ringDuration Seconds",
                        fontSize = 20.sp,
                        color = Color(0xFF0284C7),
                        fontWeight = FontWeight.ExtraBold
                    )
                    Slider(
                        value = ringDuration.toFloat(),
                        onValueChange = { ringDuration = it.toInt() },
                        valueRange = 10f..60f,
                        steps = 50,
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
                        text = "Pause Delay Between Calls",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "$delayBetween Seconds",
                        fontSize = 20.sp,
                        color = Color(0xFF16A34A),
                        fontWeight = FontWeight.ExtraBold
                    )
                    Slider(
                        value = delayBetween.toFloat(),
                        onValueChange = { delayBetween = it.toInt() },
                        valueRange = 2f..30f,
                        steps = 28,
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
