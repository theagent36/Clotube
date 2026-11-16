# ==============================================================================
# CLOTUBE v3.7 - PRODUCTION R8 / PROGUARD ZERO-BREAKAGE KEEP RULES
# ==============================================================================

# 1. Preserve All Clotube Classes, Models, DTOs & ViewModels (Prevents Gson/DataStore NPEs)
-keep class com.clotube.app.** { *; }
-keepclassmembers class com.clotube.app.** { *; }
-keep interface com.clotube.app.** { *; }

-keep class com.clotube.** { *; }
-keepclassmembers class com.clotube.** { *; }
-keep interface com.clotube.** { *; }

-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# 2. Kotlin Metadata, Reflection & Gson Serialization
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, *Annotation*
-keep class kotlin.Metadata { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
    @androidx.compose.runtime.Immutable <fields>;
    @androidx.compose.runtime.Stable <fields>;
    *** Companion;
}
-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# 3. LibVLC Native JNI, VLCVideoLayout & Surface Synchronization
-keep class org.videolan.** { *; }
-keep interface org.videolan.** { *; }
-keepclassmembers class org.videolan.** { *; }
-dontwarn org.videolan.**

-keepclasseswithmembernames class * {
    native <methods>;
}

-keepclassmembers class * {
    void onEvent(org.videolan.libvlc.MediaPlayer$Event);
}

# 4. WorkManager, Offline Download Worker & Foreground Sync Service
-keep public class androidx.work.Worker { *; }
-keep public class androidx.work.CoroutineWorker { *; }
-keep public class androidx.work.ListenableWorker { *; }
-keep public class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

-keep class com.clotube.app.workers.** { *; }
-keep class com.clotube.app.data.VideoDownloadWorker { *; }
-keep class com.clotube.app.data.DriveSyncWorker { *; }
-keep class com.clotube.app.data.ClotubeSyncService { *; }
-keep class com.clotube.app.data.DownloadRepository { *; }
-keep class com.clotube.app.data.OfflineMediaRecord { *; }
-keep enum com.clotube.app.data.DownloadStatus { *; }

# 5. DataStore Preferences & Protobuf
-keep class androidx.datastore.** { *; }
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite { *; }

# 6. MediaSessionCompat, Audio Focus & Stream Engine
-keep class com.clotube.app.media.** { *; }
-keep class android.support.v4.media.** { *; }
-keep class androidx.media.** { *; }
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-dontwarn androidx.media3.**

# 7. Google Play Services, Credential Manager & Google Drive REST API
-keep class com.google.android.gms.auth.** { *; }
-keep class com.google.android.gms.common.** { *; }
-keep class com.google.android.gms.tasks.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }
-keep class androidx.credentials.** { *; }
-dontwarn com.google.android.gms.**

-keep class com.google.api.** { *; }
-keep class com.google.apis.** { *; }
-keep class com.google.http.** { *; }
-keep interface com.google.api.client.** { *; }
-keepclassmembers class * {
    @com.google.api.client.util.Key <fields>;
}
-dontwarn com.google.api.**
-dontwarn com.google.apis.**
-dontwarn com.google.common.**
-dontwarn org.apache.http.**
-dontwarn java.awt.**

# 8. OkHttp, Coil & Kotlin Coroutines
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

-keep class coil.** { *; }
-dontwarn coil.**

-dontwarn kotlinx.coroutines.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler { *; }
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

-dontwarn sun.misc.Unsafe
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
