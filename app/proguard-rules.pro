# ==============================================================================
# CLOTUBE v4.0 - PRODUCTION R8 / PROGUARD KEEP RULES
# ==============================================================================

# 1. Preserve All @Keep Targets, Domain Models, Sync Managers, Network & Updater
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

-keep class com.clotube.app.model.** { *; }
-keepclassmembers class com.clotube.app.model.** { *; }

-keep class com.clotube.app.data.** { *; }
-keepclassmembers class com.clotube.app.data.** { *; }

-keep class com.clotube.app.sync.** { *; }
-keepclassmembers class com.clotube.app.sync.** { *; }

-keep class com.clotube.app.network.** { *; }
-keepclassmembers class com.clotube.app.network.** { *; }

-keep class com.clotube.app.update.** { *; }
-keepclassmembers class com.clotube.app.update.** { *; }

-keep class com.clotube.app.media.** { *; }
-keepclassmembers class com.clotube.app.media.** { *; }

-keep class com.clotube.app.auth.** { *; }
-keep class com.clotube.app.player.** { *; }
-keep class com.clotube.app.viewmodel.** { *; }
-keepclassmembers class com.clotube.app.viewmodel.** { *; }

# 2. Gson & JSON Reflection Protection
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, *Annotation*
-keep class kotlin.Metadata { *; }
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# 3. LibVLC Native JNI & Media Playback Engine
-keep class org.videolan.libvlc.** { *; }
-keep interface org.videolan.libvlc.** { *; }
-keep class org.videolan.medialibrary.** { *; }
-dontwarn org.videolan.**
-keepclasseswithmembernames class * {
    native <methods>;
}
-keepclassmembers class * {
    void onEvent(org.videolan.libvlc.MediaPlayer$Event);
}

# 4. Google Drive REST API, Play Services Auth & Credentials
-keep class com.google.api.** { *; }
-keep class com.google.apis.** { *; }
-keep class com.google.http.** { *; }
-keep interface com.google.api.client.** { *; }
-keep class com.google.android.gms.auth.** { *; }
-keep class com.google.android.gms.common.** { *; }
-keep class com.google.android.gms.tasks.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }
-keep class androidx.credentials.** { *; }
-keepclassmembers class * {
    @com.google.api.client.util.Key <fields>;
}
-dontwarn com.google.android.gms.**
-dontwarn com.google.api.**
-dontwarn com.google.apis.**
-dontwarn com.google.common.**
-dontwarn org.apache.http.**
-dontwarn java.awt.**

# 5. WorkManager, Services, DataStore & MediaSession
-keep public class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class com.clotube.app.workers.** { *; }
-keep class android.support.v4.media.** { *; }
-keep class androidx.media.** { *; }
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-dontwarn androidx.media3.**
-keep class androidx.datastore.** { *; }
-keepclassmembers class * extends androidx.datastore.preferences.protobuf.GeneratedMessageLite { *; }

# 6. Jetpack Compose, OkHttp, Coil & Coroutines
-keepclassmembers class * {
    @androidx.compose.runtime.Immutable <fields>;
    @androidx.compose.runtime.Stable <fields>;
    *** Companion;
}
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class coil.** { *; }
-dontwarn coil.**
-dontwarn kotlinx.coroutines.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

-dontwarn sun.misc.Unsafe
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
