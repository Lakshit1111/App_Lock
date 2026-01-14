package com.applock

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class AppLockService : Service() {
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var lastApp = ""

    override fun onCreate() {
        super.onCreate()
        try {
            startForegroundServiceStrict()
            startMonitoring()
        } catch (e: Exception) {
            e.printStackTrace()
            stopSelf() // If it fails, stop safely instead of crashing
        }
    }

    private fun startForegroundServiceStrict() {
        val channelId = "app_lock_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "App Lock Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("App Lock Active")
            .setContentText("Protecting your apps")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

        // ANDROID 14 FIX: Explicitly state the service type
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, notification)
        }
    }

    private fun startMonitoring() {
        scope.launch {
            while (isActive) {
                try {
                    checkForegroundApp()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(300)
            }
        }
    }

    private fun checkForegroundApp() {
        // Double check permissions to prevent crash
        if (!hasUsageStatsPermission()) return

        val usm = getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val time = System.currentTimeMillis()
        val events = usm.queryEvents(time - 1000, time)
        val event = UsageEvents.Event()
        
        var currentApp = ""
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                currentApp = event.packageName
            }
        }

        if (currentApp.isNotEmpty() && currentApp != lastApp && currentApp != packageName) {
            val prefs = getSharedPreferences("app_lock_prefs", Context.MODE_PRIVATE)
            if (prefs.getBoolean(currentApp, false)) {
                openLockScreen(currentApp)
            }
            lastApp = currentApp
        }
    }

    private fun openLockScreen(packageName: String) {
        try {
            val intent = Intent(this, LockScreenActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("locked_package", packageName)
            }
            startActivity(intent)
        } catch (e: Exception) {
            // This prevents crash if "Display over other apps" is missing
            e.printStackTrace()
        }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}