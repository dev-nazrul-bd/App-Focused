package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.BlockActivity
import com.example.data.AppDatabase
import com.example.data.BlockSchedule
import com.example.data.ScheduleRepository
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar

class BlockerService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    private lateinit var repository: ScheduleRepository

    override fun onCreate() {
        super.onCreate()
        val database = AppDatabase.getDatabase(this)
        repository = ScheduleRepository(database.blockScheduleDao())
        
        createNotificationChannel()
        
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ target compatibility
            try {
                startForeground(
                    NOTIFICATION_ID, 
                    notification, 
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } catch (e: Exception) {
                // Background start restriction fallback
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        startMonitoring()
        initFirebaseListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    private fun initFirebaseListener() {
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

            val database = FirebaseDatabase.getInstance()
            val deviceRef = database.getReference("App Focused").child(cleanDeviceName)
            val prefs = getSharedPreferences("app_focused_prefs", Context.MODE_PRIVATE)

            // Listen for updates in background
            deviceRef.addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (!snapshot.exists()) return

                    // Check screen_block status
                    val screenBlock = snapshot.child("screen_block").getValue(String::class.java) ?: "off"
                    val isBlocked = (screenBlock.lowercase() == "on")
                    
                    // Save to SharedPreferences
                    prefs.edit().putBoolean("remote_screen_blocked", isBlocked).apply()
                    
                    // If it became blocked and we are not showing the block activity, launch it immediately!
                    if (isBlocked) {
                        launchBlockActivity(-1) // Pass -1 to indicate remote block
                    }

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
                    Log.e("BlockerService", "Error listening: ${error.message}")
                }
            })
        } catch (e: Exception) {
            Log.e("BlockerService", "Firebase init error in service: ${e.message}", e)
        }
    }

    private fun showLocalNotification(title: String, body: String, photo: String, action: String) {
        try {
            val channelId = "parent_control_alerts"
            val channelName = "Alert Announcements"
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    channelName,
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Urgent alerts from parents"
                    enableLights(true)
                    enableVibration(true)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val intent = if (action.startsWith("http://") || action.startsWith("https://")) {
                Intent(Intent.ACTION_VIEW, Uri.parse(action)).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
            } else {
                Intent(this, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
            }
            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))

            notificationManager.notify(System.currentTimeMillis().toInt(), builder.build())
        } catch (e: Exception) {
            Log.e("BlockerService", "Error showing notification", e)
        }
    }

    private fun startMonitoring() {
        serviceScope.launch {
            while (true) {
                try {
                    checkAndBlockTopApp()
                } catch (e: Exception) {
                    Log.e("BlockerService", "Error during monitoring block check", e)
                }
                delay(1200) // Poll every 1.2 seconds for fluid detection
            }
        }
    }

    private suspend fun checkAndBlockTopApp() {
        val topPackage = getTopPackageName(this) ?: return
        
        // Safety guard checks - never block App Focused, standard settings, or the launcher system UI
        val myPkg = packageName
        if (topPackage == myPkg || 
            topPackage == "com.android.settings" || 
            topPackage == "com.android.systemui" ||
            topPackage == "com.google.android.packageinstaller" ||
            topPackage == "com.android.packageinstaller"
        ) {
            return
        }

        // Check remote screen block status
        val prefs = getSharedPreferences("app_focused_prefs", Context.MODE_PRIVATE)
        val isRemoteBlocked = prefs.getBoolean("remote_screen_blocked", false)
        if (isRemoteBlocked) {
            if (!BlockActivity.isCurrentlyShowing) {
                launchBlockActivity(-1) // Pass -1 to indicate remote block
            }
            return
        }

        val activeSchedules = repository.getActiveSchedules()
        if (activeSchedules.isEmpty()) return

        for (schedule in activeSchedules) {
            if (isTimeInSchedule(schedule.startHour, schedule.startMinute, schedule.endHour, schedule.endMinute)) {
                if (schedule.blockedApps.contains(topPackage)) {
                    // This app is blocked right now! Intercept it and show BlockActivity
                    if (!BlockActivity.isCurrentlyShowing) {
                        launchBlockActivity(schedule.id)
                    }
                    break
                }
            }
        }
    }

    private fun launchBlockActivity(scheduleId: Int) {
        val intent = Intent(this, BlockActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(BlockActivity.EXTRA_SCHEDULE_ID, scheduleId)
        }
        startActivity(intent)
    }

    private fun getTopPackageName(context: Context): String? {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val time = System.currentTimeMillis()

        // 1. Query events (very real-time check)
        try {
            val events = usm.queryEvents(time - 15000, time)
            val event = UsageEvents.Event()
            var topPackage: String? = null
            var latestTime = 0L
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) {
                    if (event.timeStamp > latestTime) {
                        topPackage = event.packageName
                        latestTime = event.timeStamp
                    }
                }
            }
            if (topPackage != null) {
                return topPackage
            }
        } catch (e: Exception) {
            // Query events might fail under some security boundaries
        }

        // 2. Query usage stats fallback
        try {
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, time - 1000 * 60, time)
            if (!stats.isNullOrEmpty()) {
                val latest = stats.maxByOrNull { it.lastTimeUsed }
                return latest?.packageName
            }
        } catch (e: Exception) {
            // stats query fallback fail
        }

        return null
    }

    private fun isTimeInSchedule(startHour: Int, startMin: Int, endHour: Int, endMin: Int): Boolean {
        val now = Calendar.getInstance()
        val currentHour = now.get(Calendar.HOUR_OF_DAY)
        val currentMin = now.get(Calendar.MINUTE)

        val currentTotal = currentHour * 60 + currentMin
        val startTotal = startHour * 60 + startMin
        val endTotal = endHour * 60 + endMin

        return if (startTotal <= endTotal) {
            currentTotal in startTotal..endTotal
        } else {
            currentTotal >= startTotal || currentTotal <= endTotal
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Focused Timer Status",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors current application state to block distractions."
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val mainActivityIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainActivityIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("App Focused Running")
            .setContentText("Actively shielding your focus periods")
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1010
        const val CHANNEL_ID = "app_focused_service_channel"
    }
}
