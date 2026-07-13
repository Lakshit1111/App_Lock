# Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Biometric
-keep class androidx.biometric.** { *; }
-dontwarn androidx.biometric.**

# App entry points
-keep class com.applock.MainActivity { *; }
-keep class com.applock.LockScreenActivity { *; }
-keep class com.applock.AppLockService { *; }

# Keep Composable functions (called reflectively in some paths)
-keepclassmembers @androidx.compose.runtime.Composable class * {
    public *;
}