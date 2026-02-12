package com.applock

import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

class LockScreenActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val lockedPackage = intent.getStringExtra("locked_package") ?: ""

        setContent {
            MaterialTheme {
                LockScreenContent(
                    packageName = lockedPackage,
                    activity = this,
                    onUnlockSuccess = { finish() }
                )
            }
        }
    }
}

@Composable
fun LockScreenContent(
    packageName: String, 
    activity: FragmentActivity,
    onUnlockSuccess: () -> Unit
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("app_lock_prefs", android.content.Context.MODE_PRIVATE)
    val savedPattern = prefs.getString("user_pattern", "") ?: ""
    
    var currentPattern by remember { mutableStateOf<List<Int>>(emptyList()) }
    var errorMessage by remember { mutableStateOf("") }

    // Biometric Logic
    LaunchedEffect(Unit) {
        val executor = ContextCompat.getMainExecutor(context)
        val biometricPrompt = BiometricPrompt(activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onUnlockSuccess()
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock App")
            .setSubtitle("Authenticate to access $packageName")
            .setNegativeButtonText("Use Pattern")
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    BackHandler {
        val homeIntent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_HOME)
            flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(homeIntent)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Locked",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = packageName, color = MaterialTheme.colorScheme.primary)
        
        Spacer(modifier = Modifier.height(48.dp))

        // Uses the corrected PatternLockView from PatternLockUtils.kt
        PatternLockView(
            currentPattern = currentPattern,
            onUpdatePattern = { newPoint ->
                if (!currentPattern.contains(newPoint)) {
                    currentPattern = currentPattern + newPoint
                }
            },
            onComplete = {
                val enteredPatternString = currentPattern.joinToString("")
                if (savedPattern.isEmpty()) {
                    errorMessage = "No pattern set! Open AppLock app to set one."
                } else if (enteredPatternString == savedPattern) {
                    onUnlockSuccess()
                } else {
                    errorMessage = "Wrong Pattern"
                    currentPattern = emptyList()
                }
            }
        )

        if (errorMessage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = errorMessage, color = Color.Red)
        }
    }
}