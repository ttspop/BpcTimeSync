# BPC Time Sync ProGuard Rules

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Prevent obfuscation of data classes used in flows
-keep class com.bpctimesync.** { *; }
