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
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
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

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkAndRequestPermissions()

        setContent {
            MaterialTheme {
                AppNavigation(
                    onStartCalls = { selectedList, ringTime, delayTime ->
                        startFajrCalls(selectedList, ringTime, delayTime)
                    },
                    onStopCalls = { stopFajrCalls() }
                )
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

    private fun startFajrCalls(contacts: List<ContactItem>, ringDuration: Int, delayBetween: Int) {
        val names = contacts.map { it.name }.toTypedArray()
        val numbers = contacts.map { it.phoneNumber }.toTypedArray()

        val intent = Intent(this, FajrCallService::class.java).apply {
            action = FajrCallService.ACTION_START
            putExtra(FajrCallService.EXTRA_NAMES, names)
            putExtra(FajrCallService.EXTRA_NUMBERS, numbers)
            putExtra(FajrCallService.EXTRA_RING_DURATION, ringDuration)
            putExtra(FajrCallService.EXTRA_DELAY_BETWEEN, delayBetween)
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
    onStartCalls: (List<ContactItem>, Int, Int) -> Unit,
    onStopCalls: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("fajr_prefs", Context.MODE_PRIVATE) }

    var currentScreen by remember { mutableStateOf(Screen.HOME) }
    var selectedContacts by remember { mutableStateOf(loadSavedSelectedContacts(prefs)) }
    var ringDuration by remember { mutableIntStateOf(prefs.getInt("ring_duration", 25)) }
    var delayBetween by remember { mutableIntStateOf(prefs.getInt("delay_between", 5)) }

    when (currentScreen) {
        Screen.HOME -> FajrHomeScreen(
            selectedCount = selectedContacts.size,
            onOpenContacts = { currentScreen = Screen.CONTACT_PICKER },
            onOpenSettings = { currentScreen = Screen.SETTINGS },
            onStart = { onStartCalls(selectedContacts, ringDuration, delayBetween) },
            onStop = onStopCalls
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
            onBack = { currentScreen = Screen.HOME },
            onSaveSettings = { newRing, newDelay ->
                ringDuration = newRing
                delayBetween = newDelay
                prefs.edit().putInt("ring_duration", newRing).putInt("delay_between", newDelay).apply()
                currentScreen = Screen.HOME
            }
        )
    }
}

@Composable
fun FajrHomeScreen(
    selectedCount: Int,
    onOpenContacts: () -> Unit,
    onOpenSettings: () -> Unit,
    onStart: () -> Unit,
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
        // Top Bar with Title and Settings Icon
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Fajr Call Companion",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1E293B)
            )
            IconButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .size(52.dp)
                    .background(Color.White, CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = Color(0xFF475569),
                    modifier = Modifier.size(30.dp)
                )
            }
        }

        // Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Current Status",
                    fontSize = 18.sp,
                    color = Color.Gray
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = FajrCallService.currentStatusMessage,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0F172A)
                )
            }
        }

        // CHOOSE LIST BUTTON
        Button(
            onClick = onOpenContacts,
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7))
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Default.List,
                    contentDescription = null,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "CHOOSE LIST",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    Text(
                        text = "$selectedCount friends selected",
                        fontSize = 16.sp,
                        color = Color.White.copy(alpha = 0.9f)
                    )
                }
            }
        }

        // GIANT START CALLS BUTTON
        Button(
            onClick = onStart,
            modifier = Modifier
                .fillMaxWidth()
                .height(110.dp),
            shape = RoundedCornerShape(22.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF16A34A))
        ) {
            Text(
                text = "START FAJR CALLS",
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White
            )
        }

        // GIANT STOP CALLS BUTTON
        Button(
            onClick = onStop,
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
            shape = RoundedCornerShape(18.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626))
        ) {
            Text(
                text = "STOP ALL CALLS",
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }

        Spacer(modifier = Modifier.height(8.dp))
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
    var selectedIds by remember {
        mutableStateOf(alreadySelected.map { it.phoneNumber }.toSet())
    }

    LaunchedEffect(Unit) {
        allContacts = fetchPhoneContacts(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Choose Friends to Call (${selectedIds.size})") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        val selectedList = allContacts.filter { selectedIds.contains(it.phoneNumber) }
                        onSaveSelection(selectedList)
                    }) {
                        Text("DONE", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF16A34A))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Mass Selection Control Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                OutlinedButton(onClick = {
                    selectedIds = allContacts.map { it.phoneNumber }.toSet()
                }) {
                    Text("Select All")
                }
                OutlinedButton(onClick = {
                    selectedIds = emptySet()
                }) {
                    Text("Deselect All")
                }
            }

            Divider()

            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                items(allContacts) { contact ->
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
                            .padding(16.dp),
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
                            }
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = contact.name,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = contact.phoneNumber,
                                fontSize = 16.sp,
                                color = Color.Gray
                            )
                        }
                    }
                    Divider(modifier = Modifier.padding(start = 56.dp))
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
    onBack: () -> Unit,
    onSaveSettings: (Int, Int) -> Unit
) {
    var ringDuration by remember { mutableIntStateOf(initialRingDuration) }
    var delayBetween by remember { mutableIntStateOf(initialDelayBetween) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Call Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
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
                .padding(padding)
                .padding(24.dp)
        ) {
            // Ring Duration Config
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Ring Duration Before Hanging Up",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "$ringDuration Seconds",
                        fontSize = 24.sp,
                        color = Color(0xFF0284C7),
                        fontWeight = FontWeight.ExtraBold
                    )
                    Slider(
                        value = ringDuration.toFloat(),
                        onValueChange = { ringDuration = it.toInt() },
                        valueRange = 10f..60f,
                        steps = 50
                    )
                }
            }

            // Delay Between Calls Config
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        text = "Pause Delay Between Calls",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "$delayBetween Seconds",
                        fontSize = 24.sp,
                        color = Color(0xFF16A34A),
                        fontWeight = FontWeight.ExtraBold
                    )
                    Slider(
                        value = delayBetween.toFloat(),
                        onValueChange = { delayBetween = it.toInt() },
                        valueRange = 2f..30f,
                        steps = 28
                    )
                }
            }
        }
    }
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
