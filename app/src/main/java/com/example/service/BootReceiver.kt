package com.example.service

import android.app.AppOpsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d("BootReceiver", "Received broadcast action: $action")
        
        if (Intent.ACTION_BOOT_COMPLETED == action || "android.intent.action.QUICKBOOT_POWERON" == action) {
            val prefs = context.getSharedPreferences("app_focused_prefs", Context.MODE_PRIVATE)
            val serviceEnabled = prefs.getBoolean("service_enabled", true)
            
            val overlayGranted = Settings.canDrawOverlays(context)
            val usageGranted = hasUsageStatsPermission(context)
            
            Log.d("BootReceiver", "Service enabled: $serviceEnabled, overlayGranted: $overlayGranted, usageGranted: $usageGranted")
            
            if (serviceEnabled && overlayGranted && usageGranted) {
                val serviceIntent = Intent(context, BlockerService::class.java)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    Log.d("BootReceiver", "Successfully started BlockerService on boot.")
                } catch (e: Exception) {
                    Log.e("BootReceiver", "Failed to start BlockerService on boot", e)
                }
            }
        }
    }

    private fun hasUsageStatsPermission(context: Context): Boolean {
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            } else {
                appOps.noteOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            }
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            false
        }
    }
}
