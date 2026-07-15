package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("AlarmReceiver", "Alarm fired! Waking up App Focused service.")
        
        val prefs = context.getSharedPreferences("app_focused_prefs", Context.MODE_PRIVATE)
        val serviceEnabled = prefs.getBoolean("service_enabled", true)
        val overlayGranted = Settings.canDrawOverlays(context)
        
        Log.d("AlarmReceiver", "Service enabled: $serviceEnabled, overlayGranted: $overlayGranted")
        
        if (serviceEnabled && overlayGranted) {
            val serviceIntent = Intent(context, BlockerService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Log.d("AlarmReceiver", "Successfully started BlockerService from alarm.")
            } catch (e: Exception) {
                Log.e("AlarmReceiver", "Failed to start BlockerService from alarm", e)
            }
        }
    }
}
