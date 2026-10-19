# ==============================================================
# Clotube R8 / ProGuard Keep Rules
# ==============================================================

# --- Keep All App Classes & Data Models to Prevent Kotlin Reflection/Gson NPEs ---
-keep class com.clotube.** { *; }
-keepclassmembers class com.clotube.** { *; }
-keep class com.clotube.app.** { *; }
-keepclassmembers class com.clotube.app.** { *; }

# --- Keep @Keep Annotated Classes ---
-keep @androidx.annotation.Keep class * { *; }
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# --- LibVLC Complete Native & JNI Hierarchy (CRITICAL) ---
-keep class org.videolan.** { *; }
-keep interface org.videolan.** { *; }
-keepclassmembers class org.videolan.** { *; }
-dontwarn org.videolan.**

# --- Google API Client, HTTP Client & Drive REST Models ---
-keep class com.google.api.** { *; }
-keep class com.google.apis.** { *; }
-keep class com.google.http.** { *; }
-keepclassmembers class * {
    @com.google.api.client.util.Key <fields>;
}
-dontwarn com.google.api.**
-dontwarn com.google.apis.**
-dontwarn com.google.common.**
-dontwarn com.google.android.gms.**
-dontwarn org.apache.http.**
-dontwarn java.awt.**
-dontwarn javax.naming.**
-dontwarn org.ietf.jgss.**

# --- AndroidX WorkManager & Background Sync Service ---
-keep class androidx.work.** { *; }
-keep public class androidx.work.Worker { *; }
-keep public class androidx.work.CoroutineWorker { *; }
-keep class * extends androidx.work.Worker { *; }
-keep class * extends androidx.work.CoroutineWorker { *; }
-keep class com.clotube.app.workers.** { *; }
-keep class com.clotube.app.data.ClotubeSyncService { *; }
-dontwarn androidx.work.**

# --- Gson / JSON Serialization & Kotlin Metadata ---
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, *Annotation*
-keep class kotlin.Metadata { *; }
-keepclassmembers class ** {
    @com.google.gson.annotations.SerializedName <fields>;
}

# --- AndroidX DataStore & Preferences ---
-keep class androidx.datastore.** { *; }
-keep class androidx.datastore.preferences.** { *; }
-dontwarn androidx.datastore.**

# --- OkHttp, Coil & Network Engine ---
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keep class coil.** { *; }
-dontwarn coil.**

# --- Kotlin Coroutines & Jetpack Compose ---
-dontwarn kotlinx.coroutines.**
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory { *; }
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler { *; }
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}
-keepclassmembers class * {
    @androidx.compose.runtime.Immutable <fields>;
    @androidx.compose.runtime.Stable <fields>;
}
