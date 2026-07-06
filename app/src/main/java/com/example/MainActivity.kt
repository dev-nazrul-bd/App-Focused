package com.example

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.ImageView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.lifecycleScope
import com.example.data.AppDatabase
import com.example.data.BlockSchedule
import com.example.data.ScheduleRepository
import com.example.service.BlockerService
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar

data class AppInfo(
    val name: String,
    val packageName: String
)

class MainActivity : ComponentActivity() {

    private lateinit var repository: ScheduleRepository
    private lateinit var prefs: SharedPreferences

    // Live permission states updated onResume
    private val overlayPermissionGranted = mutableStateOf(false)
    private val usagePermissionGranted = mutableStateOf(false)
    private val serviceEnabled = mutableStateOf(false)
    private val installedAppsList = mutableStateOf<List<AppInfo>>(emptyList())
    internal val isRemoteScreenBlocked = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val db = AppDatabase.getDatabase(this)
        repository = ScheduleRepository(db.blockScheduleDao())
        prefs = getSharedPreferences("app_focused_prefs", Context.MODE_PRIVATE)
        serviceEnabled.value = prefs.getBoolean("service_enabled", true)

        loadInstalledApps()
        requestNotificationPermission()
        initFirebaseRealtimeDatabase()

        setContent {
            MyApplicationTheme(darkTheme = false, dynamicColor = false) {
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("main_activity_scaffold")
                ) { innerPadding ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) {
                        val overlayGranted by overlayPermissionGranted
                        val usageGranted by usagePermissionGranted
                        val remoteBlocked by isRemoteScreenBlocked

                        if (remoteBlocked) {
                            RemoteBlockedScreen(
                                onContactDeveloper = {
                                    try {
                                        val contactIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://dev-nazrul.web.app/contact"))
                                        startActivity(contactIntent)
                                    } catch (e: Exception) {
                                        Log.e("MainActivity", "Error launching developer contact link", e)
                                    }
                                },
                                onExit = {
                                    finish()
                                }
                            )
                        } else if (!overlayGranted || !usageGranted) {
                            PermissionOnboardingScreen(
                                overlayGranted = overlayGranted,
                                usageGranted = usageGranted,
                                onRequestOverlay = { requestOverlayPermission() },
                                onRequestUsage = { requestUsageStatsPermission() }
                            )
                        } else {
                            val schedulesState = repository.allSchedules.collectAsState(initial = emptyList())
                            AppFocusedDashboard(
                                schedules = schedulesState.value,
                                installedApps = installedAppsList.value,
                                serviceActive = serviceEnabled.value,
                                onToggleService = { active ->
                                    toggleBlockerService(active)
                                },
                                onAddSchedule = { schedule ->
                                    saveSchedule(schedule)
                                },
                                onDeleteSchedule = { schedule ->
                                    deleteSchedule(schedule)
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
        // Sync back-end service status based on shared preference
        syncBlockerService()
        val localPrefs = getSharedPreferences("app_focused_prefs", Context.MODE_PRIVATE)
        isRemoteScreenBlocked.value = localPrefs.getBoolean("remote_screen_blocked", false)
    }

    private fun checkPermissions() {
        overlayPermissionGranted.value = Settings.canDrawOverlays(this)
        usagePermissionGranted.value = hasUsageStatsPermission()
    }

    private fun hasUsageStatsPermission(): Boolean {
        return try {
            val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    packageName
                )
            } else {
                appOps.noteOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }

    private fun requestOverlayPermission() {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (e: Exception) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
            startActivity(intent)
        }
    }

    private fun requestUsageStatsPermission() {
        try {
            val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            // fallback generic settings
            val intent = Intent(Settings.ACTION_SETTINGS)
            startActivity(intent)
        }
    }

    private fun toggleBlockerService(active: Boolean) {
        serviceEnabled.value = active
        prefs.edit().putBoolean("service_enabled", active).apply()
        syncBlockerService()
    }

    private fun syncBlockerService() {
        val active = serviceEnabled.value && overlayPermissionGranted.value && usagePermissionGranted.value
        val serviceIntent = Intent(this, BlockerService::class.java)
        if (active) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to start foreground blocker service", e)
            }
        } else {
            stopService(serviceIntent)
        }
    }

    private fun saveSchedule(schedule: BlockSchedule) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                if (schedule.id == 0) {
                    repository.insert(schedule)
                } else {
                    repository.update(schedule)
                }
            }
            // Trigger service checking refresh immediately after schedule changes
            syncBlockerService()
        }
    }

    private fun deleteSchedule(schedule: BlockSchedule) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                repository.delete(schedule)
            }
            syncBlockerService()
        }
    }

    private fun loadInstalledApps() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val pm = packageManager
                val intent = Intent(Intent.ACTION_MAIN, null).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                }
                val resolveInfos = pm.queryIntentActivities(intent, 0)
                val apps = resolveInfos.map { info ->
                    AppInfo(
                        name = info.loadLabel(pm).toString(),
                        packageName = info.activityInfo.packageName
                    )
                }
                    .distinctBy { it.packageName }
                    .sortedBy { it.name }
                
                withContext(Dispatchers.Main) {
                    installedAppsList.value = apps
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to load installed apps list", e)
            }
        }
    }
}

@Composable
fun PermissionOnboardingScreen(
    overlayGranted: Boolean,
    usageGranted: Boolean,
    onRequestOverlay: () -> Unit,
    onRequestUsage: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFFFCFCFD))
            .padding(24.dp),
        verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 40.dp)
        ) {
            Surface(
                modifier = Modifier.size(80.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Shield,
                        contentDescription = "Shield Guard",
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Welcome to App Focused",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1D2939),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "To block distracting apps during your preset hours, we need the following system permissions. They stay entirely on your device.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF667085),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        // Two Permission Cards
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .padding(vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Permission 1: Overlay
            PermissionItemCard(
                title = "Overlay Permission (Draw Over Apps)",
                description = "Required to display custom motivational focus shields when blocked apps are opened.",
                isGranted = overlayGranted,
                onConfigure = onRequestOverlay,
                testTag = "overlay_permission_card"
            )

            // Permission 2: Usage Stats
            PermissionItemCard(
                title = "Usage Access",
                description = "Needed to detect when you start distracting applications so we can shield your screen on time.",
                isGranted = usageGranted,
                onConfigure = onRequestUsage,
                testTag = "usage_permission_card"
            )
        }

        Text(
            text = "Status: Waiting for permission setup...",
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF98A2B3),
            modifier = Modifier.padding(bottom = 16.dp)
        )
    }
}

@Composable
fun PermissionItemCard(
    title: String,
    description: String,
    isGranted: Boolean,
    onConfigure: () -> Unit,
    testTag: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag(testTag),
        colors = CardDefaults.cardColors(
            containerColor = if (isGranted) Color(0xFFF0F9FF) else Color.White
        ),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(
            1.dp, 
            if (isGranted) Color(0xFFB9E6FE) else Color(0xFFEAECF0)
        )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1D2939)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF475467)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            if (isGranted) {
                Surface(
                    shape = CircleShape,
                    color = Color(0xFFD1FADF),
                    modifier = Modifier.size(36.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Granted",
                            tint = Color(0xFF039855),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            } else {
                Button(
                    onClick = onConfigure,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Text("Grant", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@Composable
fun AppFocusedDashboard(
    schedules: List<BlockSchedule>,
    installedApps: List<AppInfo>,
    serviceActive: Boolean,
    onToggleService: (Boolean) -> Unit,
    onAddSchedule: (BlockSchedule) -> Unit,
    onDeleteSchedule: (BlockSchedule) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }
    var selectedScheduleForEdit by remember { mutableStateOf<BlockSchedule?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFFF9FAFB))
        ) {
            // Header panel with app logo and protection shield status
            Surface(
                color = Color.White,
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "App Focused",
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Black,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Automated distraction block schedules",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF667085)
                            )
                        }

                        // Shield indication icon
                        Surface(
                            shape = CircleShape,
                            color = if (serviceActive) Color(0xFFD1FADF) else Color(0xFFF2F4F7),
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (serviceActive) Icons.Default.Shield else Icons.Default.LockOpen,
                                    contentDescription = "Shield Status",
                                    tint = if (serviceActive) Color(0xFF039855) else Color(0xFF475467)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Master toggle Switch container
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = if (serviceActive) Color(0xFFF0F9FF) else Color(0xFFF2F4F7),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 10.dp)
                                .fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.PowerSettingsNew,
                                    contentDescription = null,
                                    tint = if (serviceActive) MaterialTheme.colorScheme.primary else Color(0xFF475467)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "Focused Protection Shield",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (serviceActive) Color(0xFF026AA2) else Color(0xFF344054)
                                    )
                                    Text(
                                        text = if (serviceActive) "Shield active and monitoring apps" else "Shield inactive. Schedules won't block items.",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (serviceActive) Color(0xFF026AA2).copy(alpha = 0.8f) else Color(0xFF667085)
                                    )
                                }
                            }

                            Switch(
                                checked = serviceActive,
                                onCheckedChange = onToggleService,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = MaterialTheme.colorScheme.primary
                                )
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Body list showing active schedules
            if (schedules.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = Color(0xFFF4F3FF),
                            modifier = Modifier.size(72.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.EventNote,
                                    contentDescription = "Plan",
                                    modifier = Modifier.size(36.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Text(
                            text = "No schedules set yet",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = Color(0xFF344054)
                        )
                        
                        Spacer(modifier = Modifier.height(6.dp))
                        
                        Text(
                            text = "Tap the '+' button below to configure daily blocked hours and select distracting apps.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF667085),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 80.dp)
                ) {
                    item {
                        Text(
                            text = "ACTIVE SCEDULES",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF667085),
                            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp, top = 8.dp)
                        )
                    }

                    items(schedules, key = { it.id }) { schedule ->
                        ScheduleViewCard(
                            schedule = schedule,
                            onEdit = {
                                selectedScheduleForEdit = schedule
                                showDialog = true
                            },
                            onDelete = { onDeleteSchedule(schedule) },
                            onToggleStatus = { active ->
                                onAddSchedule(schedule.copy(isActive = active))
                            }
                        )
                    }
                }
            }
        }

        // FAB to add schedule
        SmallFloatingActionButton(
            onClick = {
                selectedScheduleForEdit = null
                showDialog = true
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp)
                .size(56.dp)
                .testTag("add_schedule_fab"),
            shape = CircleShape,
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = Color.White
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Add Schedule",
                modifier = Modifier.size(28.dp)
            )
        }

        if (showDialog) {
            AddEditScheduleDialog(
                schedule = selectedScheduleForEdit,
                installedApps = installedApps,
                onDismiss = { showDialog = false },
                onSave = { saved ->
                    onAddSchedule(saved)
                    showDialog = false
                }
            )
        }
    }
}

@Composable
fun ScheduleViewCard(
    schedule: BlockSchedule,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggleStatus: (Boolean) -> Unit
) {
    val formatHour = { h: Int, m: Int ->
        val amPm = if (h >= 12) "PM" else "AM"
        val displayHour = when {
            h == 0 -> 12
            h > 12 -> h - 12
            else -> h
        }
        String.format("%02d:%02d %s", displayHour, m, amPm)
    }

    val timeString = "${formatHour(schedule.startHour, schedule.startMinute)} - ${formatHour(schedule.endHour, schedule.endMinute)}"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("schedule_card_${schedule.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (schedule.isActive) Color.White else Color(0xFFF9FAFB)
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (schedule.isActive) Color(0xFFEAECF0) else Color(0xFFEAECF0).copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = if (schedule.isActive) MaterialTheme.colorScheme.primaryContainer else Color(0xFFF2F4F7),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = when (schedule.blockType.uppercase()) {
                                    "IMAGE" -> Icons.Default.Image
                                    "WEBSITE" -> Icons.Default.Language
                                    "VIDEO" -> Icons.Default.PlayCircleFilled
                                    "PDF" -> Icons.Default.PictureAsPdf
                                    else -> Icons.Default.Timelapse
                                },
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (schedule.isActive) MaterialTheme.colorScheme.primary else Color(0xFF667085)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column {
                        Text(
                            text = schedule.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (schedule.isActive) Color(0xFF1D2939) else Color(0xFF667085)
                        )
                        Text(
                            text = timeString,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = if (schedule.isActive) MaterialTheme.colorScheme.primary else Color(0xFF98A2B3)
                        )
                    }
                }

                Switch(
                    checked = schedule.isActive,
                    onCheckedChange = onToggleStatus,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Sub-info: blocked apps package counts, block type
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val appIndicator = when (schedule.blockedApps.size) {
                        0 -> "No apps blocked"
                        1 -> "1 app blocked"
                        else -> "${schedule.blockedApps.size} apps blocked"
                    }

                    SuggestionChip(
                        onClick = {},
                        label = { Text(appIndicator, fontSize = 11.sp) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = Color(0xFFF3F4F6)
                        )
                    )

                    SuggestionChip(
                        onClick = {},
                        label = { Text("Display: ${schedule.blockType}", fontSize = 11.sp) },
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = Color(0xFFEFF6FF)
                        )
                    )
                }

                Row {
                    IconButton(
                        onClick = onEdit,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit Schedule",
                            tint = Color(0xFF475467),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = onDelete,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete Schedule",
                            tint = Color(0xFFD92D20),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AddEditScheduleDialog(
    schedule: BlockSchedule?,
    installedApps: List<AppInfo>,
    onDismiss: () -> Unit,
    onSave: (BlockSchedule) -> Unit
) {
    var name by remember { mutableStateOf(schedule?.name ?: "Focus Schedule") }
    
    // Start Time states
    val initialStartHour = schedule?.startHour ?: 8
    var startHour by remember { mutableStateOf(if (initialStartHour > 12) initialStartHour - 12 else if (initialStartHour == 0) 12 else initialStartHour) }
    var startMin by remember { mutableStateOf(schedule?.startMinute ?: 0) }
    var startAmPm by remember { mutableStateOf(if (initialStartHour >= 12) "PM" else "AM") }
    
    // End Time states
    val initialEndHour = schedule?.endHour ?: 17
    var endHour by remember { mutableStateOf(if (initialEndHour > 12) initialEndHour - 12 else if (initialEndHour == 0) 12 else initialEndHour) }
    var endMin by remember { mutableStateOf(schedule?.endMinute ?: 0) }
    var endAmPm by remember { mutableStateOf(if (initialEndHour >= 12) "PM" else "AM") }
    
    // Custom media action choices
    var blockType by remember { mutableStateOf(schedule?.blockType ?: "DEFAULT") }
    var blockContent by remember { mutableStateOf(schedule?.blockContent ?: "") }
    
    // Set of selected apps list packages
    val selectedPackages = remember { mutableStateListOf<String>().apply { 
        schedule?.blockedApps?.let { addAll(it) } 
    } }

    // Tab of Apps Selection Search
    var appSearchQuery by remember { mutableStateOf("") }

    val context = LocalContext.current

    val imageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val path = copyUriToLocal(context, it, "img", "jpg")
            if (path != null) {
                blockContent = path
            }
        }
    }

    val videoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val path = copyUriToLocal(context, it, "vid", "mp4")
            if (path != null) {
                blockContent = path
            }
        }
    }

    val pdfLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val path = copyUriToLocal(context, it, "pdf", "pdf")
            if (path != null) {
                blockContent = path
            }
        }
    }
    
    val filteredApps = remember(appSearchQuery, installedApps) {
        if (appSearchQuery.isEmpty()) {
            installedApps
        } else {
            installedApps.filter { it.name.contains(appSearchQuery, ignoreCase = true) }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White),
            color = Color.White
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Toolbar headers
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }

                    Text(
                        text = if (schedule == null) "Create Schedule" else "Edit Schedule",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1D2939)
                    )

                    Button(
                        onClick = {
                            if (name.isBlank()) name = "Focus Period"
                            
                            // Convert 12 hour to 24 hour integers
                            val computedStart = to24Hour(startHour, startAmPm)
                            val computedEnd = to24Hour(endHour, endAmPm)

                            val outSchedule = BlockSchedule(
                                id = schedule?.id ?: 0,
                                name = name,
                                startHour = computedStart,
                                startMinute = startMin,
                                endHour = computedEnd,
                                endMinute = endMin,
                                blockedApps = selectedPackages.toList(),
                                isActive = schedule?.isActive ?: true,
                                blockType = blockType,
                                blockContent = blockContent
                            )
                            onSave(outSchedule)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Save", fontWeight = FontWeight.Bold)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. Name Input
                    item {
                        Column {
                            Text(
                                text = "Schedule Name",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF344054)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            TextField(
                                value = name,
                                onValueChange = { name = it },
                                placeholder = { Text("e.g. Study Block, Night Calm") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth().testTag("schedule_name_input"),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color(0xFFF9FAFB),
                                    unfocusedContainerColor = Color(0xFFF9FAFB)
                                )
                            )
                        }
                    }

                    // 2. Select Time Intervals
                    item {
                        Column {
                            Text(
                                text = "Block Active Hours",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF344054)
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // Start Hours picker card wrapper
                                Card(
                                    modifier = Modifier.weight(1f),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF9FAFB)),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEAECF0))
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text("From / Start", fontSize = 12.sp, color = Color(0xFF667085), fontWeight = FontWeight.Medium)
                                        Spacer(modifier = Modifier.height(8.dp))
                                        
                                        TimeCompactPicker(
                                            hour = startHour,
                                            minute = startMin,
                                            ampm = startAmPm,
                                            onTimeSelected = { h, m, ap ->
                                                startHour = h
                                                startMin = m
                                                startAmPm = ap
                                            }
                                        )
                                    }
                                }

                                // End hour card wrapper
                                Card(
                                    modifier = Modifier.weight(1f),
                                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF9FAFB)),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFEAECF0))
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text("To / End", fontSize = 12.sp, color = Color(0xFF667085), fontWeight = FontWeight.Medium)
                                        Spacer(modifier = Modifier.height(8.dp))
                                        
                                        TimeCompactPicker(
                                            hour = endHour,
                                            minute = endMin,
                                            ampm = endAmPm,
                                            onTimeSelected = { h, m, ap ->
                                                endHour = h
                                                endMin = m
                                                endAmPm = ap
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 3. Choice of full-screen block visual media
                    item {
                        Column {
                            Text(
                                text = "When Blocked: Display Media Design",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF344054)
                            )
                            Text(
                                text = "Assign what is shown when a user breaches the focus session.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF667085)
                            )
                            Spacer(modifier = Modifier.height(10.dp))

                            // Horizontal Tab chips to select modes
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(bottom = 6.dp)
                            ) {
                                val modes = listOf("DEFAULT", "IMAGE", "WEBSITE", "VIDEO", "PDF")
                                items(modes) { mode ->
                                    FilterChip(
                                        selected = blockType.uppercase() == mode,
                                        onClick = { 
                                            blockType = mode 
                                            if (mode == "WEBSITE" && blockContent.isEmpty()) {
                                                blockContent = "https://en.wikipedia.org/wiki/Deep_Work"
                                            } else if (mode != "WEBSITE" && mode != "DEFAULT" && (blockContent.startsWith("http") || blockContent.isEmpty())) {
                                                blockContent = ""
                                            }
                                        },
                                        label = { Text(mode) },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = when (mode) {
                                                    "IMAGE" -> Icons.Default.Image
                                                    "WEBSITE" -> Icons.Default.Language
                                                    "VIDEO" -> Icons.Default.PlayArrow
                                                    "PDF" -> Icons.Default.PictureAsPdf
                                                    else -> Icons.Default.TextSnippet
                                                },
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            when (blockType.uppercase()) {
                                "IMAGE" -> {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .border(1.dp, Color(0xFFEAECF0), RoundedCornerShape(12.dp))
                                            .background(Color(0xFFF9FAFB))
                                            .padding(16.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        if (blockContent.isNotEmpty()) {
                                            Text(
                                                text = "Selected Image File:",
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(0xFF344054)
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = blockContent.substringAfterLast("/"),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFF667085),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(12.dp))
                                        }

                                        Button(
                                            onClick = { imageLauncher.launch("image/*") },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                            ),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Image,
                                                contentDescription = "Select Image"
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                if (blockContent.isNotEmpty()) "Change Image" else "Select Image from Device",
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }
                                "VIDEO" -> {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .border(1.dp, Color(0xFFEAECF0), RoundedCornerShape(12.dp))
                                            .background(Color(0xFFF9FAFB))
                                            .padding(16.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        if (blockContent.isNotEmpty()) {
                                            Text(
                                                text = "Selected Video File:",
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(0xFF344054)
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = blockContent.substringAfterLast("/"),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFF667085),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(12.dp))
                                        }

                                        Button(
                                            onClick = { videoLauncher.launch("video/*") },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                            ),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = "Select Video"
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                if (blockContent.isNotEmpty()) "Change Video" else "Select Video from Device",
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }
                                "PDF" -> {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .border(1.dp, Color(0xFFEAECF0), RoundedCornerShape(12.dp))
                                            .background(Color(0xFFF9FAFB))
                                            .padding(16.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        if (blockContent.isNotEmpty()) {
                                            Text(
                                                text = "Selected PDF File:",
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color(0xFF344054)
                                            )
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = blockContent.substringAfterLast("/"),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFF667085),
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Spacer(modifier = Modifier.height(12.dp))
                                        }

                                        Button(
                                            onClick = { pdfLauncher.launch("application/pdf") },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                                            ),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PictureAsPdf,
                                                contentDescription = "Select PDF"
                                            )
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Text(
                                                if (blockContent.isNotEmpty()) "Change PDF" else "Select PDF from Device",
                                                fontWeight = FontWeight.SemiBold
                                            )
                                        }
                                    }
                                }
                                "WEBSITE" -> {
                                    TextField(
                                        value = blockContent,
                                        onValueChange = { blockContent = it },
                                        placeholder = { Text("Enter Website link url (https://...)") },
                                        label = { Text("Website Link") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = TextFieldDefaults.colors(
                                            focusedContainerColor = Color(0xFFFFFEFD)
                                        )
                                    )
                                }
                                else -> {
                                    TextField(
                                        value = blockContent,
                                        onValueChange = { blockContent = it },
                                        placeholder = { Text("Enter focus motivation custom quote or message") },
                                        label = { Text("Custom Message") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = TextFieldDefaults.colors(
                                            focusedContainerColor = Color(0xFFFFFEFD)
                                        )
                                    )
                                }
                            }
                        }
                    }

                    // 4. Listing Installed applications with multi-checkboxes
                    item {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Select Blocked Applications",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF344054)
                                )

                                Text(
                                    text = "${selectedPackages.size} Selected",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))

                            // Search query for installed apps
                            OutlinedTextField(
                                value = appSearchQuery,
                                onValueChange = { appSearchQuery = it },
                                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search") },
                                placeholder = { Text("Search apps (e.g. YouTube, Facebook)", fontSize = 14.sp) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(52.dp),
                                shape = RoundedCornerShape(10.dp),
                                singleLine = true
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 200.dp, max = 340.dp)
                                    .border(1.dp, Color(0xFFEAECF0), RoundedCornerShape(12.dp))
                                    .clip(RoundedCornerShape(12.dp)),
                                color = Color(0xFFFCFCFD)
                            ) {
                                if (filteredApps.isEmpty()) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("No applications found", color = Color(0xFF98A2B3))
                                    }
                                } else {
                                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                                        items(filteredApps, key = { it.packageName }) { app ->
                                            val isChecked = selectedPackages.contains(app.packageName)
                                            
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable {
                                                        if (isChecked) {
                                                            selectedPackages.remove(app.packageName)
                                                        } else {
                                                            selectedPackages.add(app.packageName)
                                                        }
                                                    }
                                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                // App Checkbox
                                                Checkbox(
                                                    checked = isChecked,
                                                    onCheckedChange = { checked ->
                                                        if (checked == true) {
                                                            selectedPackages.add(app.packageName)
                                                        } else {
                                                            selectedPackages.remove(app.packageName)
                                                        }
                                                    }
                                                )

                                                Spacer(modifier = Modifier.width(4.dp))

                                                // Installed Icon View Helper
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    modifier = Modifier.size(36.dp),
                                                    color = Color.White
                                                ) {
                                                    AndroidView(
                                                        factory = { context ->
                                                            ImageView(context).apply {
                                                                try {
                                                                    val icon = context.packageManager.getApplicationIcon(app.packageName)
                                                                    setImageDrawable(icon)
                                                                } catch (e: Exception) {
                                                                    setImageResource(android.R.drawable.sym_def_app_icon)
                                                                }
                                                            }
                                                        },
                                                        modifier = Modifier.fillMaxSize()
                                                    )
                                                }

                                                Spacer(modifier = Modifier.width(12.dp))

                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = app.name,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = Color(0xFF344054),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                    Text(
                                                        text = app.packageName,
                                                        fontSize = 11.sp,
                                                        color = Color(0xFF667085),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                            Divider(color = Color(0xFFF2F4F7), thickness = 1.dp)
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
}

@Composable
fun TimeCompactPicker(
    hour: Int,
    minute: Int,
    ampm: String,
    onTimeSelected: (Int, Int, String) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Hour pick
        Box(modifier = Modifier.weight(1f)) {
            var expanded by remember { mutableStateOf(false) }
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(String.format("%02d Hr", hour), fontSize = 13.sp)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                for (h in 1..12) {
                    DropdownMenuItem(
                        text = { Text("$h") },
                        onClick = {
                            onTimeSelected(h, minute, ampm)
                            expanded = false
                        }
                    )
                }
            }
        }

        // Minutes pick
        Box(modifier = Modifier.weight(1f)) {
            var expanded by remember { mutableStateOf(false) }
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Text(String.format("%02d Min", minute), fontSize = 13.sp)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(16.dp))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                val mins = listOf(0, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55)
                for (m in mins) {
                    DropdownMenuItem(
                        text = { Text(String.format("%02d", m)) },
                        onClick = {
                            onTimeSelected(hour, m, ampm)
                            expanded = false
                        }
                    )
                }
            }
        }

        // AM/PM pick
        Row(
            modifier = Modifier
                .border(1.dp, Color(0xFFD0D5DD), RoundedCornerShape(8.dp))
                .clip(RoundedCornerShape(8.dp))
                .height(34.dp)
        ) {
            Box(
                modifier = Modifier
                    .background(if (ampm == "AM") MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .clickable { onTimeSelected(hour, minute, "AM") }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                Text("AM", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (ampm == "AM") MaterialTheme.colorScheme.primary else Color(0xFF475467))
            }
            Box(
                modifier = Modifier
                    .background(if (ampm == "PM") MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                    .clickable { onTimeSelected(hour, minute, "PM") }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .fillMaxHeight(),
                contentAlignment = Alignment.Center
            ) {
                Text("PM", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (ampm == "PM") MaterialTheme.colorScheme.primary else Color(0xFF475467))
            }
        }
    }
}

private fun to24Hour(hour12: Int, ampm: String): Int {
    return when (ampm.uppercase()) {
        "AM" -> if (hour12 == 12) 0 else hour12
        "PM" -> if (hour12 == 12) 12 else hour12 + 12
        else -> hour12
    }
}

fun copyUriToLocal(context: Context, uri: Uri, prefix: String, extension: String): String? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val storageDir = context.filesDir
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }
        val file = java.io.File(storageDir, "${prefix}_${System.currentTimeMillis()}.$extension")
        file.outputStream().use { outputStream ->
            inputStream.use { inputStream ->
                inputStream.copyTo(outputStream)
            }
        }
        file.absolutePath
    } catch (e: Exception) {
        Log.e("MainActivity", "Error copying content URI to local storage", e)
        null
    }
}

class FirebaseState {
    companion object {
        var initialized = false
    }
}

fun MainActivity.requestNotificationPermission() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 101)
        }
    }
}

fun MainActivity.initFirebaseRealtimeDatabase() {
    try {
        val manufacturer = Build.MANUFACTURER
        val model = Build.MODEL
        val rawDeviceName = if (model.startsWith(manufacturer)) {
            model
        } else {
            "$manufacturer $model"
        }
        val cleanDeviceName = rawDeviceName.replace(".", "_")
            .replace("#", "_")
            .replace("$", "_")
            .replace("[", "_")
            .replace("]", "_")
            .trim()
            
        Log.d("FirebaseRTDB", "Registering device name: $cleanDeviceName")

        val database = FirebaseDatabase.getInstance()
        val deviceRef = database.getReference("App Focused").child(cleanDeviceName)

        // Step 1: Upload structure if it doesn't exist
        deviceRef.addListenerForSingleValueEvent(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) {
                    val initialStructure = mapOf(
                        "screen_block" to "off",
                        "notification" to mapOf(
                            "action" to "https://classroom.google.com",
                            "body" to "Please return to your books immediately and lock unnecessary screens.",
                            "photo" to "https://images.unsplash.com/photo-1497633762265-9d179a990aa6?w=600&auto=format&fit=crop",
                            "status" to "displayed",
                            "timestamp" to System.currentTimeMillis(),
                            "title" to "Study Time Announcement! 📚"
                        )
                    )
                    deviceRef.setValue(initialStructure)
                }
            }
            override fun onCancelled(error: DatabaseError) {
                Log.e("FirebaseRTDB", "Error on single read: ${error.message}")
            }
        })

        // Step 2: Listen for updates
        deviceRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!snapshot.exists()) return

                // Check screen_block status
                val screenBlock = snapshot.child("screen_block").getValue(String::class.java) ?: "off"
                val isBlocked = (screenBlock.lowercase() == "on")
                isRemoteScreenBlocked.value = isBlocked
                val localPrefs = getSharedPreferences("app_focused_prefs", Context.MODE_PRIVATE)
                localPrefs.edit().putBoolean("remote_screen_blocked", isBlocked).apply()

                // Check notification status
                val notifNode = snapshot.child("notification")
                if (notifNode.exists()) {
                    val status = notifNode.child("status").getValue(String::class.java) ?: ""
                    if (status.lowercase() == "sent") {
                        val title = notifNode.child("title").getValue(String::class.java) ?: "Announcement!"
                        val body = notifNode.child("body").getValue(String::class.java) ?: ""
                        val photo = notifNode.child("photo").getValue(String::class.java) ?: ""
                        val action = notifNode.child("action").getValue(String::class.java) ?: ""
                        
                        // 1. Show notification on the device
                        showLocalNotification(title, body, photo, action)
                        
                        // 2. Set status to "displayed" in database
                        deviceRef.child("notification").child("status").setValue("displayed")
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("FirebaseRTDB", "Error listening: ${error.message}")
            }
        })

    } catch (e: Exception) {
        Log.e("FirebaseRTDB", "Firebase init error: ${e.message}", e)
    }
}

fun MainActivity.showLocalNotification(title: String, body: String, photo: String, action: String) {
    try {
        val channelId = "parent_control_alerts"
        val channelName = "Alert Announcements"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                channelName,
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Urgent alerts from parents"
                enableLights(true)
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = if (action.startsWith("http://") || action.startsWith("https://")) {
            Intent(Intent.ACTION_VIEW, Uri.parse(action))
        } else {
            Intent(this, MainActivity::class.java)
        }
        val pendingIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )

        val builder = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(body))

        notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
    } catch (e: Exception) {
        Log.e("FirebaseRTDB", "Error showing notification", e)
    }
}

@Composable
private fun RemoteBlockedScreen(onContactDeveloper: () -> Unit, onExit: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .background(Color(0xFFEF4444).copy(alpha = 0.1f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = "Device Locked",
                    tint = Color(0xFFEF4444),
                    modifier = Modifier.size(40.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "স্ক্রিন লক করা হয়েছে!",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Screen Blocked",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF94A3B8),
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "নিরাপত্তাজনিত কারণে আপনার স্ক্রিন ব্লক করা হয়েছে। অনুগ্রহ করে ডেভেলপারের সাথে যোগাযোগ করুন বা পরবর্তীতে চেষ্টা করুন।",
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFFCBD5E1),
                textAlign = TextAlign.Center,
                lineHeight = 24.sp
            )

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onContactDeveloper,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF4F46E5),
                    contentColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Icon(Icons.Default.ContactSupport, contentDescription = "Contact Dev")
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "ডেভেলপারের সাথে যোগাযোগ করুন",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = onExit,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = Color(0xFF94A3B8)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text(
                    text = "অ্যাপ বন্ধ করুন (Exit App)",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }
        }
    }
}
