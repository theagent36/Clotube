# ==============================================================================
# CLOTUBE - ZERO-BREAKAGE PRODUCTION R8 / PROGUARD RULES
# ==============================================================================

# ------------------------------------------------------------------------------
# 1. COMPLETE APPLICATION NAMESPACE SHIELD (PREVENTS GSON / REFLECTION NPEs)
# ------------------------------------------------------------------------------
# Keep every class, inner data class, enum, companion, and field inside Clotube
# so Gson, SharedPreferences, DataStore, and Compose Navigation never fail.
-keep class com.clotube.** { *; }
-keepclassmembers class com.clotube.** { *; }
-keep interface com.clotube.** { *; }

-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# ------------------------------------------------------------------------------
# 2. KOTLIN METADATA, GSON & JSON SERIALIZATION
# ------------------------------------------------------------------------------
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, *Annotation*
-keep class kotlin.Metadata { *; }

-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class com.google.gson.** { *; }
-keep class com.google.gson.reflect.TypeToken { *; }
-keep class * extends com.google.gson.reflect.TypeToken

# ------------------------------------------------------------------------------
# 3. LIBVLC NATIVE JNI, AWINDOW & MEDIA PLAYBACK ENGINE (CRITICAL)
# ------------------------------------------------------------------------------
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

# ------------------------------------------------------------------------------
# 4. GOOGLE PLAY SERVICES, CREDENTIALS & GOOGLE DRIVE REST API v3
# ------------------------------------------------------------------------------
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

-dontwarn com.google.api.**
-dontwarn com.google.apis.**
-dontwarn com.google.common.**
-dontwarn com.google.android.gms.**
-dontwarn org.apache.http.**
-dontwarn java.awt.**

# ------------------------------------------------------------------------------
# 5. WORKMANAGER, FOREGROUND SERVICES & MEDIASESSION
# ------------------------------------------------------------------------------
-keep public class androidx.work.Worker { *; }
-keep public class androidx.work.CoroutineWorker { *; }
-keep public class androidx.work.ListenableWorker { *; }
-keep public class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

-keep class android.support.v4.media.** { *; }
-keep class androidx.media.** { *; }
-keep class androidx.media3.** { *; }
-keep interface androidx.media3.** { *; }
-dontwarn androidx.media3.**

# ------------------------------------------------------------------------------
# 6. JETPACK DATASTORE, COIL, OKHTTP & COROUTINES
# ------------------------------------------------------------------------------
-keep class androidx.datastore.** { *; }
-keep class coil.** { *; }
-dontwarn coil.**

-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

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

-dontwarn kotlinx.coroutines.**
-dontwarn sun.misc.Unsafe
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
