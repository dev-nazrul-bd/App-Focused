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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
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
