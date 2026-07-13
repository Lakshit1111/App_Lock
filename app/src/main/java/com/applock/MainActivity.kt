package com.applock

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: android.graphics.Bitmap,
    var isLocked: Boolean = false
)

class MainActivity : FragmentActivity() {

    var isSelfLocked = mutableStateOf(false)
    private var pendingPermissionFlow = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AppLockScreen(
                    isSelfLocked = isSelfLocked.value,
                    activity = this,
                    onSelfUnlock = {
                        getSharedPreferences("app_lock_prefs", Context.MODE_PRIVATE)
                            .edit().putBoolean("self_locked", false).apply()
                        isSelfLocked.value = false
                    },
                    markPendingPermissionFlow = { pendingPermissionFlow = true }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        pendingPermissionFlow = false
        val prefs = getSharedPreferences("app_lock_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("self_locked", false)) {
            isSelfLocked.value = true
        }
        // Start service only if BOTH permissions are granted
        if (checkUsagePermission(this) && Settings.canDrawOverlays(this)) {
            val intent = Intent(this, AppLockService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (pendingPermissionFlow || isFinishing) return
        val prefs = getSharedPreferences("app_lock_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("lock_self", false)) {
            prefs.edit().putBoolean("self_locked", true).apply()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLockScreen(
    isSelfLocked: Boolean,
    activity: FragmentActivity,
    onSelfUnlock: () -> Unit,
    markPendingPermissionFlow: () -> Unit
) {
    val context = LocalContext.current
    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var hasUsagePermission by remember { mutableStateOf(checkUsagePermission(context)) }
    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var showPatternSetup by remember { mutableStateOf(false) } // Setup Dialog State
    var hasAccessibilityPermission by remember { mutableStateOf(isAccessibilityEnabled(context)) }

    val scope = rememberCoroutineScope()
    val prefs = context.getSharedPreferences("app_lock_prefs", Context.MODE_PRIVATE)

    // Self-lock overlay: shown when the app itself is locked
    if (isSelfLocked) {
        SelfLockScreen(activity = activity, onUnlockSuccess = onSelfUnlock)
        return
    }

    LaunchedEffect(hasUsagePermission, hasOverlayPermission, hasAccessibilityPermission) {
        if (hasUsagePermission && hasOverlayPermission && hasAccessibilityPermission) {
            installedApps = withContext(Dispatchers.IO) {
                getInstalledApps(context, prefs)
            }
        }
    }

    if (showPatternSetup) {
        PatternSetupDialog(
            onDismiss = { showPatternSetup = false },
            onSave = { patternString ->
                prefs.edit().putString("user_pattern", patternString).apply()
                showPatternSetup = false
                Toast.makeText(context, "Pattern Saved!", Toast.LENGTH_SHORT).show()
            }
        )
    }

    val requestUsage = { markPendingPermissionFlow(); requestUsagePermission(context) }
    val requestOverlay = { markPendingPermissionFlow(); requestOverlayPermission(context) }
    val requestAccessibility = { markPendingPermissionFlow(); requestAccessibilityPermission(context) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Lock") },
                actions = {
                    IconButton(onClick = { showPatternSetup = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Set Pattern")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (!hasUsagePermission || !hasOverlayPermission || !hasAccessibilityPermission) {
                PermissionRequestCard(
                    hasUsage = hasUsagePermission,
                    hasOverlay = hasOverlayPermission,
                    hasAccessibility = hasAccessibilityPermission,
                    onRequestUsage = requestUsage,
                    onRequestOverlay = requestOverlay,
                    onRequestAccessibility = requestAccessibility,
                    onCheckAgain = {
                        hasUsagePermission = checkUsagePermission(context)
                        hasOverlayPermission = Settings.canDrawOverlays(context)
                        hasAccessibilityPermission = isAccessibilityEnabled(context)
                    }
                )
            } else {
                if (installedApps.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(contentPadding = PaddingValues(8.dp)) {
                        item {
                            // Helper text for the user
                            Text(
                                "Click the Gear icon ⚙️ to set your Lock Pattern",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                        items(installedApps) { app ->
                            AppLockItem(app = app, onToggleLock = { isLocked ->
                                // Self-lock uses the 'lock_self' key instead of the package name
                                val key = if (app.packageName == context.packageName) {
                                    "lock_self"
                                } else {
                                    app.packageName
                                }
                                scope.launch(Dispatchers.IO) {
                                    prefs.edit().putBoolean(key, isLocked).apply()

                                    // Update local state UI
                                    val newList = installedApps.toMutableList()
                                    val index = newList.indexOfFirst { it.packageName == app.packageName }
                                    if (index != -1) {
                                        newList[index] = newList[index].copy(isLocked = isLocked)
                                        installedApps = newList
                                    }
                                }
                            })
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SelfLockScreen(activity: FragmentActivity, onUnlockSuccess: () -> Unit) {
    LockScreenContent(
        packageName = activity.packageName,
        activity = activity,
        onUnlockSuccess = onUnlockSuccess
    )
}

@Composable
fun PatternSetupDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var currentPattern by remember { mutableStateOf<List<Int>>(emptyList()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set New Pattern") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Draw your new pattern below:")
                Spacer(modifier = Modifier.height(16.dp))
                
                // This references the external file PatternLockUtils.kt
                PatternLockView(
                    currentPattern = currentPattern,
                    onUpdatePattern = { if (!currentPattern.contains(it)) currentPattern = currentPattern + it },
                    onComplete = { /* Do nothing here, wait for Save button */ }
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                if (currentPattern.size < 4) {
                    // Optional: Show error for short pattern if desired
                } else {
                    onSave(currentPattern.joinToString(""))
                }
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            Button(onClick = { currentPattern = emptyList() }) {
                Text("Reset")
            }
        }
    )
}

@Composable
fun PermissionRequestCard(
    hasUsage: Boolean,
    hasOverlay: Boolean,
    hasAccessibility: Boolean, // 1. Added this parameter
    onRequestUsage: () -> Unit,
    onRequestOverlay: () -> Unit,
    onRequestAccessibility: () -> Unit, // 2. Added this parameter
    onCheckAgain: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("Permissions Required", style = MaterialTheme.typography.headlineSmall)
            Spacer(modifier = Modifier.height(16.dp))

            if (!hasUsage) {
                Button(onClick = onRequestUsage) { Text("1. Grant Usage Access") }
                Text("Needed to detect running apps", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (!hasOverlay) {
                Button(onClick = onRequestOverlay) { Text("2. Grant Overlay Permission") }
                Text("Needed to show lock screen", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(8.dp))
            }

            // 3. Added the UI for the new accessibility permission
            if (!hasAccessibility) {
                Button(onClick = onRequestAccessibility) { Text("3. Enable Accessibility Service") }
                Text("Needed to detect when apps are opened", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(8.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))
            TextButton(onClick = onCheckAgain) { Text("I have granted permissions") }
        }
    }
}

@Composable
fun AppLockItem(app: AppInfo, onToggleLock: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                bitmap = app.icon.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(app.appName, modifier = Modifier.weight(1f))
            Switch(checked = app.isLocked, onCheckedChange = onToggleLock)
        }
    }
}

fun checkUsagePermission(context: Context): Boolean {
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    val mode = appOps.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        android.os.Process.myUid(),
        context.packageName
    )
    return mode == AppOpsManager.MODE_ALLOWED
}

fun requestUsagePermission(context: Context) {
    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
}

fun requestOverlayPermission(context: Context) {
    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) 
    context.startActivity(intent)
}

fun getInstalledApps(context: Context, prefs: android.content.SharedPreferences): List<AppInfo> {
    val pm = context.packageManager
    val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
    val otherApps = apps.filter {
        pm.getLaunchIntentForPackage(it.packageName) != null && it.packageName != context.packageName
    }.map {
        AppInfo(
            it.packageName,
            it.loadLabel(pm).toString(),
            it.loadIcon(pm).toBitmap(),
            prefs.getBoolean(it.packageName, false)
        )
    }.sortedBy { it.appName }

    // Prepend a pinned self entry so the user can toggle locking AppLock itself.
    val selfApp = pm.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
    val selfInfo = AppInfo(
        context.packageName,
        "App Lock",
        pm.getApplicationIcon(selfApp).toBitmap(),
        prefs.getBoolean("lock_self", false)
    )
    return listOf(selfInfo) + otherApps
}


fun isAccessibilityEnabled(context: Context): Boolean {
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    )
    return enabledServices?.contains(context.packageName) == true
}

fun requestAccessibilityPermission(context: Context) {
    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}