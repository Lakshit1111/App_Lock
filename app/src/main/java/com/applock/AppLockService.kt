package com.applock

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

class AppLockService : AccessibilityService() {

    private var lastPackageName = ""
    
    // 1. The Cache: A list of locked apps kept in memory for instant access
    private val lockedAppsCache = mutableSetOf<String>()

    // Recently unlocked apps — prevents re-locking immediately after authentication
    private val recentlyUnlocked = mutableSetOf<String>()

    // Currently locked app whose lock screen is visible — prevents duplicate lock screens
    private var lockedPackageOnScreen: String? = null

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var prefs: SharedPreferences
    private lateinit var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener

    companion object {
        private var instance: AppLockService? = null

        fun unlockApp(packageName: String) {
            instance?.writeLog("unlockApp called for: $packageName")
            instance?.addRecentlyUnlocked(packageName)
            instance?.clearLockScreen()
        }

        fun notifyLockScreenDismissed() {
            instance?.writeLog("notifyLockScreenDismissed called")
            instance?.clearLockScreen()
        }
    }

    private fun writeLog(message: String) {
        try {
            val logDir = getExternalFilesDir(null)
            if (logDir != null) {
                val logFile = File(logDir, "applock_debug.log")
                val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(System.currentTimeMillis())
                logFile.appendText("$timestamp | $message\n")
            }
        } catch (e: Exception) {
            Log.e("AppLockDebug", "Failed to write log", e)
        }
    }

    private fun clearLogFile() {
        try {
            val logDir = getExternalFilesDir(null)
            if (logDir != null) {
                val logFile = File(logDir, "applock_debug.log")
                logFile.delete()
            }
        } catch (e: Exception) {}
    }

    private fun addRecentlyUnlocked(packageName: String) {
        recentlyUnlocked.add(packageName)
        handler.postDelayed({
            recentlyUnlocked.remove(packageName)
        }, 2000)
    }

    private fun clearLockScreen() {
        lockedPackageOnScreen = null
    }

    private fun isSystemPackage(packageName: String): Boolean {
        return packageName.startsWith("com.android.systemui") ||
            packageName.startsWith("com.android.permissioncontroller") ||
            packageName.startsWith("com.google.android.permissioncontroller") ||
            packageName.startsWith("com.android.packageinstaller") ||
            packageName.startsWith("com.miui") ||
            packageName.startsWith("com.samsung") ||
            packageName.startsWith("com.huawei") ||
            packageName.startsWith("com.oppo") ||
            packageName.startsWith("com.coloros") ||
            packageName.endsWith(".ime") ||
            packageName.endsWith(".inputmethod") ||
            packageName.contains(".inputmethod.") ||
            packageName == "android" ||
            packageName == "com.android.settings"
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        clearLogFile()
        writeLog("Service connected")
        prefs = getSharedPreferences("app_lock_prefs", Context.MODE_PRIVATE)

        // 2. Initial Load: Read database once when service starts
        updateCompleteCache()

        // 3. The Notification Listener: 
        // When you change settings in MainActivity, this listener fires specifically 
        // to update our memory cache. No need to re-read the whole database.
        prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            if (key != null) {
                updateSingleAppInCache(key)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)
    }

    /**
     * Reads the specific change and updates only that entry in our memory list.
     */
    // private fun updateSingleAppInCache(packageName: String) {
    //     val isLocked = prefs.getBoolean(packageName, false)
    //     if (isLocked) {
    //         lockedAppsCache.add(packageName)
    //     } else {
    //         lockedAppsCache.remove(packageName)
    //     }
    // }

    private fun updateSingleAppInCache(key: String) {
        try {
            val isLocked = prefs.getBoolean(key, false)
            if (isLocked) {
                lockedAppsCache.add(key)
            } else {
                lockedAppsCache.remove(key)
            }
        } catch (e: ClassCastException) {
            // The key was not a boolean (e.g., "user_pattern" is a String).
            // We can safely ignore this change since it's not a package name lock state.
        }
    }

    /**
     * Reads all prefs to build the initial cache.
     */
    private fun updateCompleteCache() {
        lockedAppsCache.clear()
        val allEntries = prefs.all
        for ((key, value) in allEntries) {
            // If the value is 'true', add it to our locked list
            if (value is Boolean && value == true) {
                lockedAppsCache.add(key)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return

            // Prevent infinite loop by ignoring our own app
            if (packageName == this.packageName) return

            // Skip system UI / input methods / OEM system packages entirely
            // These cause noise and reset state — only real app switches matter
            if (isSystemPackage(packageName)) return

            writeLog("event: pkg=$packageName | lastPkg=$lastPackageName | onScreen=$lockedPackageOnScreen | recentlyUnlocked=$recentlyUnlocked | inCache=${lockedAppsCache.contains(packageName)}")

            if (lockedPackageOnScreen == packageName) {
                writeLog("-> skipped: lock screen already showing")
                return
            }

            if (recentlyUnlocked.contains(packageName)) {
                writeLog("-> skipped: recently unlocked")
                return
            }

            if (packageName == lastPackageName) {
                writeLog("-> skipped: same as last package")
                return
            }

            if (lockedAppsCache.contains(packageName)) {
                writeLog("-> LOCKED: showing lock screen for $packageName")
                lockedPackageOnScreen = packageName
                showLockScreen(packageName)
            } else {
                writeLog("-> not locked, updating lastPackageName")
                lastPackageName = packageName
            }
        }
    }

    private fun showLockScreen(packageName: String) {
        val intent = Intent(this, LockScreenActivity::class.java).apply {
            // Updated flags for a forceful overlay
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or 
                Intent.FLAG_ACTIVITY_CLEAR_TASK or 
                Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
            putExtra("locked_package", packageName)
        }
        startActivity(intent)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        handler.removeCallbacksAndMessages(null)
        if (::prefs.isInitialized) {
            prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        }
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        // Required method
    }
}