# ==============================================================================
# CLOTUBE - PRODUCTION R8 / PROGUARD KEEP RULES
# ==============================================================================

# ------------------------------------------------------------------------------
# 1. Core Domain Models, DTOs & DataStore Preferences
# ------------------------------------------------------------------------------
-keep class com.clotube.app.model.** { *; }
-keepclassmembers class com.clotube.app.model.** { *; }

-keep class com.clotube.app.data.** { *; }
-keepclassmembers class com.clotube.app.data.** { *; }

-keep class com.clotube.app.data.metadata.** { *; }
-keepclassmembers class com.clotube.app.data.metadata.** { *; }

-keep class com.clotube.app.viewmodel.** { *; }

-keep class androidx.datastore.preferences.** { *; }
-keep class androidx.datastore.core.** { *; }

# ------------------------------------------------------------------------------
# 2. LibVLC Native JNI & Media Playback Engine
# ------------------------------------------------------------------------------
-keep class org.videolan.libvlc.** { *; }
-keep interface org.videolan.libvlc.** { *; }
-keep class org.videolan.medialibrary.** { *; }
-dontwarn org.videolan.libvlc.**
-dontwarn org.videolan.medialibrary.**

-keepclasseswithmembernames class * {
    native <methods>;
}

-keepclassmembers class * {
    void onEvent(org.videolan.libvlc.MediaPlayer$Event);
}

# ------------------------------------------------------------------------------
# 3. WorkManager & Background Download Workers
# ------------------------------------------------------------------------------
-keep public class androidx.work.Worker { *; }
-keep public class androidx.work.CoroutineWorker { *; }
-keep public class androidx.work.ListenableWorker { *; }
-keep public class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

-keep class com.clotube.app.workers.** { *; }
-keep class com.clotube.app.data.DriveSyncWorker { *; }
-keep class com.clotube.app.data.ClotubeSyncService { *; }

# ------------------------------------------------------------------------------
# 4. MediaSessionCompat, Audio Focus & Telephony Interruption
# ------------------------------------------------------------------------------
-keep class com.clotube.app.media.** { *; }
-keep class android.support.v4.media.** { *; }
-keep class androidx.media.** { *; }

# ------------------------------------------------------------------------------
# 5. Google Play Services & Google Drive REST API
# ------------------------------------------------------------------------------
-keep class com.google.android.gms.auth.api.** { *; }
-keep class com.google.android.gms.auth.api.signin.** { *; }
-keep class com.google.android.gms.common.** { *; }
-keep class com.google.android.gms.tasks.** { *; }
-dontwarn com.google.android.gms.**

-keep class com.google.api.services.drive.** { *; }
-keep class com.google.api.client.** { *; }
-keep interface com.google.api.client.** { *; }

-keepclassmembers class * {
    @com.google.api.client.util.Key <fields>;
}

-dontwarn com.google.api.client.**
-dontwarn com.google.api.services.drive.**
-dontwarn com.google.common.**
-dontwarn org.apache.http.**

# ------------------------------------------------------------------------------
# 6. OkHttp, Networking & Coil
# ------------------------------------------------------------------------------
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

# ------------------------------------------------------------------------------
# 7. Jetpack Compose & Kotlin Coroutines
# ------------------------------------------------------------------------------
-keepclassmembers class * {
    @androidx.compose.runtime.Immutable <fields>;
    @androidx.compose.runtime.Stable <fields>;
    *** Companion;
}

-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler { *; }
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

-dontwarn sun.misc.Unsafe
-dontwarn javax.annotation.**
