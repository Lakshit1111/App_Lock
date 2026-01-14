package com.applock

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class AppInfo(
    val packageName: String,
    val appName: String,
    val icon: android.graphics.Bitmap,
    var isLocked: Boolean = false
)

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                AppLockScreen()
            }
        }
    }

    override fun onResume() {
        super.onResume()
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLockScreen() {
    val context = LocalContext.current
    var installedApps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var hasUsagePermission by remember { mutableStateOf(checkUsagePermission(context)) }
    var hasOverlayPermission by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    
    val scope = rememberCoroutineScope()
    val prefs = context.getSharedPreferences("app_lock_prefs", Context.MODE_PRIVATE)

    LaunchedEffect(hasUsagePermission, hasOverlayPermission) {
        if (hasUsagePermission && hasOverlayPermission) {
            installedApps = withContext(Dispatchers.IO) {
                getInstalledApps(context, prefs)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Lock") },
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
            if (!hasUsagePermission || !hasOverlayPermission) {
                PermissionRequestCard(
                    hasUsage = hasUsagePermission,
                    hasOverlay = hasOverlayPermission,
                    onRequestUsage = { requestUsagePermission(context) },
                    onRequestOverlay = { requestOverlayPermission(context) },
                    onCheckAgain = { 
                        hasUsagePermission = checkUsagePermission(context)
                        hasOverlayPermission = Settings.canDrawOverlays(context)
                    }
                )
            } else {
                if (installedApps.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(contentPadding = PaddingValues(8.dp)) {
                        items(installedApps) { app ->
                            AppLockItem(app = app, onToggleLock = { isLocked ->
                                scope.launch(Dispatchers.IO) {
                                    prefs.edit().putBoolean(app.packageName, isLocked).apply()
                                    val newList = installedApps.toMutableList()
                                    val index = newList.indexOfFirst { it.packageName == app.packageName }
                                    if(index != -1) {
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
fun PermissionRequestCard(
    hasUsage: Boolean,
    hasOverlay: Boolean,
    onRequestUsage: () -> Unit,
    onRequestOverlay: () -> Unit,
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
    context.startActivity(intent)
}

fun getInstalledApps(context: Context, prefs: android.content.SharedPreferences): List<AppInfo> {
    val pm = context.packageManager
    val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
    return apps.filter {
        pm.getLaunchIntentForPackage(it.packageName) != null && it.packageName != context.packageName
    }.map {
        AppInfo(
            it.packageName,
            it.loadLabel(pm).toString(),
            it.loadIcon(pm).toBitmap(),
            prefs.getBoolean(it.packageName, false)
        )
    }.sortedBy { it.appName }
}