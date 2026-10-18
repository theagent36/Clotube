# ==============================================================
# Clotube R8 / ProGuard Keep Rules
# ==============================================================

# --------------------------------------------------------------------------
# 1. LibVLC Native Player Engine & JNI Bindings (CRITICAL)
# --------------------------------------------------------------------------
-keep class org.videolan.libvlc.** { *; }
-keep interface org.videolan.libvlc.** { *; }
-keepclassmembers class org.videolan.libvlc.** {
    native <methods>;
    *;
}
-dontwarn org.videolan.libvlc.**

# --------------------------------------------------------------------------
# 2. AndroidX WorkManager & Background Sync Service
# --------------------------------------------------------------------------
-keep class androidx.work.** { *; }
-keep public class androidx.work.Worker { *; }
-keep public class androidx.work.CoroutineWorker { *; }
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }
-keep public class com.clotube.app.data.DriveSyncWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep public class com.clotube.app.data.ClotubeSyncService { *; }
-dontwarn androidx.work.**

# --------------------------------------------------------------------------
# 3. Google Drive REST API & OAuth Keep Rules
# --------------------------------------------------------------------------
-keep class com.google.api.services.drive.** { *; }
-keep class com.google.api.client.** { *; }
-keep interface com.google.api.client.** { *; }
-keep class com.google.android.gms.auth.** { *; }
-keep class com.google.android.gms.auth.api.** { *; }
-keep class com.google.android.gms.common.** { *; }
-keepclassmembers class * {
    @com.google.api.client.util.Key <fields>;
}
-dontwarn com.google.api.client.**
-dontwarn com.google.api.services.drive.**
-dontwarn com.google.common.**
-dontwarn com.google.android.gms.**
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**
-dontwarn org.apache.http.**

# --------------------------------------------------------------------------
# 4. AndroidX DataStore & Preferences
# --------------------------------------------------------------------------
-keep class androidx.datastore.** { *; }
-keep class androidx.datastore.preferences.** { *; }
-dontwarn androidx.datastore.**

# --------------------------------------------------------------------------
# 5. Clotube Domain Models & State Objects (Preserves DataStore & JSON fields)
# --------------------------------------------------------------------------
-keep class com.clotube.app.model.** { *; }
-keepclassmembers class com.clotube.app.model.** { *; }
-keep class com.clotube.app.data.** { *; }
-keepclassmembers class com.clotube.app.data.** { *; }

# Gson serialization rules
-keepattributes *Annotation*
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# --------------------------------------------------------------------------
# 6. OkHttp, Coil & Network Engine
# --------------------------------------------------------------------------
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class coil.** { *; }
-dontwarn coil.**

# --------------------------------------------------------------------------
# 7. Kotlin Coroutines & Jetpack Compose
# --------------------------------------------------------------------------
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler { *; }
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-keepclassmembers class * {
    @androidx.compose.runtime.Immutable <fields>;
    @androidx.compose.runtime.Stable <fields>;
}
