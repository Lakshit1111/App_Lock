package com.applock

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

// Note: Change ComponentActivity to FragmentActivity for Biometric support
class LockScreenActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val lockedPackage = intent.getStringExtra("locked_package") ?: ""

        setContent {
            MaterialTheme {
                LockScreenContent(
                    packageName = lockedPackage,
                    activity = this,
                    onUnlockSuccess = {
                        finish() // Close lock screen, revealing the app
                    }
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
    
    // Retrieve saved pattern (e.g., "0125")
    val savedPattern = prefs.getString("user_pattern", "") ?: ""
    
    // State for the drawn pattern
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
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    // If bio fails or is canceled, user relies on Pattern
                }
            })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock App")
            .setSubtitle("Authenticate to access $packageName")
            .setNegativeButtonText("Use Pattern")
            .build()

        biometricPrompt.authenticate(promptInfo)
    }

    // Handle Back Button (Go Home)
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
        Text(
            text = packageName, // Display package name or app name
            color = MaterialTheme.colorScheme.primary
        )
        
        Spacer(modifier = Modifier.height(48.dp))

        // Pattern Drawing Area
        PatternLockView(
            currentPattern = currentPattern,
            onUpdatePattern = { newPoint ->
                if (!currentPattern.contains(newPoint)) {
                    currentPattern = currentPattern + newPoint
                }
            },
            onComplete = {
                // Convert list of Ints (0-8) to String "012..."
                val enteredPatternString = currentPattern.joinToString("")
                
                if (savedPattern.isEmpty()) {
                    errorMessage = "No pattern set! Open AppLock app to set one."
                } else if (enteredPatternString == savedPattern) {
                    onUnlockSuccess()
                } else {
                    errorMessage = "Wrong Pattern"
                    currentPattern = emptyList() // Reset
                }
            }
        )

        if (errorMessage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(text = errorMessage, color = Color.Red)
        }
    }
}

@Composable
fun PatternLockView(
    currentPattern: List<Int>,
    onUpdatePattern: (Int) -> Unit,
    onComplete: () -> Unit
) {
    // 3x3 Grid
    val dotCount = 3
    val dots = (0 until 9).toList()

    Box(
        modifier = Modifier
            .size(300.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val index = getDotIndex(offset, size.width, size.height)
                        if (index != -1) onUpdatePattern(index)
                    },
                    onDragEnd = { onComplete() },
                    onDrag = { change, _ ->
                        val index = getDotIndex(change.position, size.width, size.height)
                        if (index != -1) onUpdatePattern(index)
                    }
                )
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height
            val cellWidth = width / 3
            val cellHeight = height / 3

            // Draw connecting lines
            if (currentPattern.size > 1) {
                for (i in 0 until currentPattern.size - 1) {
                    val startDot = currentPattern[i]
                    val endDot = currentPattern[i+1]
                    
                    val startX = (startDot % 3) * cellWidth + (cellWidth / 2)
                    val startY = (startDot / 3) * cellHeight + (cellHeight / 2)
                    val endX = (endDot % 3) * cellWidth + (cellWidth / 2)
                    val endY = (endDot / 3) * cellHeight + (cellHeight / 2)

                    drawLine(
                        color = Color.Blue,
                        start = Offset(startX, startY),
                        end = Offset(endX, endY),
                        strokeWidth = 10f,
                        cap = StrokeCap.Round
                    )
                }
            }

            // Draw Dots
            dots.forEach { index ->
                val col = index % 3
                val row = index / 3
                val cx = col * cellWidth + (cellWidth / 2)
                val cy = row * cellHeight + (cellHeight / 2)
                
                val isSelected = currentPattern.contains(index)
                val radius = if (isSelected) 25f else 15f
                val color = if (isSelected) Color.Blue else Color.Gray

                drawCircle(
                    color = color,
                    center = Offset(cx, cy),
                    radius = radius
                )
            }
        }
    }
}

// Helper to map touch coordinates to grid index (0-8)
fun getDotIndex(offset: Offset, width: Float, height: Float): Int {
    if (offset.x < 0 || offset.x > width || offset.y < 0 || offset.y > height) return -1
    
    val col = (offset.x / (width / 3)).toInt()
    val row = (offset.y / (height / 3)).toInt()
    
    // Bounds check
    if (col in 0..2 && row in 0..2) {
        return row * 3 + col
    }
    return -1
}