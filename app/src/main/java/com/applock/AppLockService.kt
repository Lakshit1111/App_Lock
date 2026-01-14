package com.applock

import android.app.*
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class AppLockService : Service() {
    private val scope = CoroutineScope(Dispatchers.Default + Job())
    private var lastApp = ""

    override fun onCreate() {
        super.onCreate()
        startForeground(1, createNotification())
        startMonitoring()
    }

    private fun createNotification(): Notification {
        val channelId = "app_lock_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "App Lock Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("App Lock Active")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .build()
    }

    private fun startMonitoring() {
        scope.launch {
            while (isActive) {
                checkForegroundApp()
                delay(300) // Check every 300ms
            }
        }
    }

    private fun checkForegroundApp() {
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
                // IMPORTANT: Launch Lock Screen
                val intent = Intent(this, LockScreenActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    putExtra("locked_package", currentApp)
                }
                startActivity(intent)
            }
            lastApp = currentApp
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}