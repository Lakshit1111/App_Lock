package com.applock

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.view.accessibility.AccessibilityEvent

class AppLockService : AccessibilityService() {

    private var lastPackageName = ""
    
    // 1. The Cache: A list of locked apps kept in memory for instant access
    private val lockedAppsCache = mutableSetOf<String>()
    
    private lateinit var prefs: SharedPreferences
    private lateinit var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener

    override fun onServiceConnected() {
        super.onServiceConnected()
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
    private fun updateSingleAppInCache(packageName: String) {
        val isLocked = prefs.getBoolean(packageName, false)
        if (isLocked) {
            lockedAppsCache.add(packageName)
        } else {
            lockedAppsCache.remove(packageName)
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
        // We only care if the window state changed (an app was opened/switched)
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val packageName = event.packageName?.toString() ?: return

            // Prevent infinite loop by ignoring our own app
            if (packageName == this.packageName) return

            // Optimization: Don't re-check if we are still on the same app
            if (packageName == lastPackageName) return

            lastPackageName = packageName

            // 4. The Optimized Check:
            // We check the 'lockedAppsCache' (Memory) instead of 'prefs' (Database).
            if (lockedAppsCache.contains(packageName)) {
                showLockScreen(packageName)
            }
        }
    }

    private fun showLockScreen(packageName: String) {
        val intent = Intent(this, LockScreenActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra("locked_package", packageName)
        }
        startActivity(intent)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        // Clean up the listener to prevent memory leaks
        if (::prefs.isInitialized) {
            prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        }
        return super.onUnbind(intent)
    }

    override fun onInterrupt() {
        // Required method
    }
}