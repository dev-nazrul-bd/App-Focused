package com.example.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.example.data.BlockSchedule
import java.util.Calendar

object AlarmScheduler {

    private const val TAG = "AlarmScheduler"

    fun scheduleNextWakeup(context: Context, schedules: List<BlockSchedule>) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        
        val activeSchedules = schedules.filter { it.isActive }
        if (activeSchedules.isEmpty()) {
            cancelWakeup(context, alarmManager)
            Log.d(TAG, "No active schedules. Cancelled existing alarms.")
            return
        }

        val now = Calendar.getInstance()
        var nearestWakeupTime: Long = Long.MAX_VALUE
        var selectedScheduleName = ""

        for (schedule in activeSchedules) {
            val startHour = schedule.startHour
            val startMinute = schedule.startMinute

            // Monitoring window starts 20 minutes before the block starts
            val startTotal = startHour * 60 + startMinute
            val preWakeStartTotal = (startTotal - 20 + 1440) % 1440

            val targetCal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, preWakeStartTotal / 60)
                set(Calendar.MINUTE, preWakeStartTotal % 60)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }

            // If the wakeup time is in the past for today, the next occurrence is tomorrow
            if (targetCal.timeInMillis <= now.timeInMillis) {
                targetCal.add(Calendar.DAY_OF_YEAR, 1)
            }

            if (targetCal.timeInMillis < nearestWakeupTime) {
                nearestWakeupTime = targetCal.timeInMillis
                selectedScheduleName = schedule.name
            }
        }

        if (nearestWakeupTime != Long.MAX_VALUE) {
            val intent = Intent(context, AlarmReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        if (alarmManager.canScheduleExactAlarms()) {
                            alarmManager.setExactAndAllowWhileIdle(
                                AlarmManager.RTC_WAKEUP,
                                nearestWakeupTime,
                                pendingIntent
                            )
                        } else {
                            alarmManager.setAndAllowWhileIdle(
                                AlarmManager.RTC_WAKEUP,
                                nearestWakeupTime,
                                pendingIntent
                            )
                        }
                    } else {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP,
                            nearestWakeupTime,
                            pendingIntent
                        )
                    }
                } else {
                    alarmManager.set(
                        AlarmManager.RTC_WAKEUP,
                        nearestWakeupTime,
                        pendingIntent
                    )
                }
                
                val debugCal = Calendar.getInstance().apply { timeInMillis = nearestWakeupTime }
                Log.d(TAG, "Scheduled next wakeup alarm at ${debugCal.time} for schedule: $selectedScheduleName")
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException scheduling exact alarm. Falling back to non-exact.", e)
                try {
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        nearestWakeupTime,
                        pendingIntent
                    )
                } catch (ex: Exception) {
                    Log.e(TAG, "Failed fallback alarm schedule", ex)
                }
            }
        }
    }

    private fun cancelWakeup(context: Context, alarmManager: AlarmManager) {
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            0,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }
}
