package com.clotube.app.data

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.Serializable
import java.util.Locale

/**
 * Descriptor for a saved Google Drive account profile.
 */
@Keep
data class SavedAccountProfile(
    @SerializedName("email") val email: String,
    @SerializedName("displayName") val displayName: String,
    @SerializedName("photoUrl") val photoUrl: String? = null,
    @SerializedName("lastActiveAt") val lastActiveAt: Long = System.currentTimeMillis()
) : Serializable

@Keep
private data class ProfileRegistry(
    @SerializedName("activeEmail") val activeEmail: String? = null,
    @SerializedName("profiles") val profiles: List<SavedAccountProfile> = emptyList()
) : Serializable

/**
 * Manages multi-account profiles with isolated per-email local disk storage.
 * Registry persisted atomically to [clotube_profiles_registry.json].
 */
object ProfileManager {

    private const val TAG = "ProfileManager"
    private const val REGISTRY_FILE_NAME = "clotube_profiles_registry.json"

    private val mutex = Mutex()
    private val gson = Gson()
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _savedProfiles = MutableStateFlow<List<SavedAccountProfile>>(emptyList())
    val savedProfiles: StateFlow<List<SavedAccountProfile>> = _savedProfiles.asStateFlow()

    private val _activeProfileEmail = MutableStateFlow<String?>(null)
    val activeProfileEmail: StateFlow<String?> = _activeProfileEmail.asStateFlow()

    @Volatile
    private var isInitialized = false

    private fun getRegistryFile(context: Context): File =
        File(context.filesDir, REGISTRY_FILE_NAME)

    /**
     * Sanitizes an email into a safe directory name:
     * lowercase, trimmed, with any character outside [a-z0-9._-] replaced with '_'.
     * Returns "default" if null or blank.
     */
    fun sanitizeEmail(email: String?): String {
        if (email.isNullOrBlank()) return "default"
        val clean = email.trim().lowercase(Locale.ROOT)
        val sanitized = clean.replace(Regex("[^a-z0-9._-]"), "_")
        return sanitized.ifBlank { "default" }
    }

    /**
     * Returns the isolated storage directory for the target email (or current active profile).
     */
    fun getProfileDir(context: Context, email: String? = _activeProfileEmail.value): File {
        val dir = File(context.filesDir, "profiles/${sanitizeEmail(email)}")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    /**
     * Loads the profile registry synchronously if not already initialized.
     */
    fun ensureLoadedSync(context: Context) {
        if (isInitialized) return
        synchronized(this) {
            if (isInitialized) return
            loadRegistryLocked(context)
            isInitialized = true
        }
    }

    /**
     * Loads the profile registry from disk into memory.
     */
    suspend fun ensureLoaded(context: Context) = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext
        mutex.withLock {
            if (isInitialized) return@withLock
            loadRegistryLocked(context)
            isInitialized = true
        }
    }

    private fun loadRegistryLocked(context: Context) {
        val file = getRegistryFile(context)
        if (file.exists() && file.length() > 0) {
            try {
                val json = file.readText(Charsets.UTF_8)
                val type = object : TypeToken<ProfileRegistry>() {}.type
                val registry = gson.fromJson<ProfileRegistry>(json, type)
                if (registry != null) {
                    _savedProfiles.value = registry.profiles
                    _activeProfileEmail.value = registry.activeEmail
                    Log.i(TAG, "Loaded ${registry.profiles.size} profiles. Active: ${registry.activeEmail}")
                    return
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read profile registry: ${e.message}")
            }
        }
        _savedProfiles.value = emptyList()
        _activeProfileEmail.value = null
    }

    private fun persistRegistryLocked(context: Context) {
        try {
            val registry = ProfileRegistry(
                activeEmail = _activeProfileEmail.value,
                profiles = _savedProfiles.value
            )
            val json = gson.toJson(registry)
            val file = getRegistryFile(context)
            val tempFile = File(context.filesDir, "$REGISTRY_FILE_NAME.tmp")
            tempFile.writeText(json, Charsets.UTF_8)
            if (!tempFile.renameTo(file)) {
                file.writeText(json, Charsets.UTF_8)
                tempFile.delete()
            }
            Log.d(TAG, "Persisted registry: active=${registry.activeEmail}, count=${registry.profiles.size}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write profile registry: ${e.message}", e)
        }
    }

    /**
     * Registers a new account profile or activates an existing one.
     */
    suspend fun registerOrActivateProfile(
        context: Context,
        email: String,
        displayName: String,
        photoUrl: String?
    ): SavedAccountProfile = withContext(Dispatchers.IO) {
        ensureLoaded(context)
        mutex.withLock {
            val cleanEmail = email.trim().lowercase(Locale.ROOT)
            val currentList = _savedProfiles.value.toMutableList()
            val existingIndex = currentList.indexOfFirst { it.email.equals(cleanEmail, ignoreCase = true) }

            val profile = if (existingIndex >= 0) {
                val existing = currentList[existingIndex]
                val updated = existing.copy(
                    displayName = displayName.ifBlank { existing.displayName },
                    photoUrl = photoUrl ?: existing.photoUrl,
                    lastActiveAt = System.currentTimeMillis()
                )
                currentList[existingIndex] = updated
                updated
            } else {
                val newProfile = SavedAccountProfile(
                    email = cleanEmail,
                    displayName = displayName.ifBlank { "User" },
                    photoUrl = photoUrl,
                    lastActiveAt = System.currentTimeMillis()
                )
                currentList.add(newProfile)
                newProfile
            }

            // Ensure profile directory exists
            getProfileDir(context, cleanEmail)

            _savedProfiles.value = currentList.sortedByDescending { it.lastActiveAt }
            _activeProfileEmail.value = cleanEmail
            persistRegistryLocked(context)

            Log.i(TAG, "Active profile set to: $cleanEmail")
            profile
        }
    }

    /**
     * Switches the active profile to the specified email.
     */
    suspend fun switchToProfile(
        context: Context,
        email: String
    ): SavedAccountProfile? = withContext(Dispatchers.IO) {
        ensureLoaded(context)
        mutex.withLock {
            val cleanEmail = email.trim().lowercase(Locale.ROOT)
            val currentList = _savedProfiles.value.toMutableList()
            val index = currentList.indexOfFirst { it.email.equals(cleanEmail, ignoreCase = true) }
            if (index < 0) {
                Log.w(TAG, "Cannot switch to unknown profile: $cleanEmail")
                return@withLock null
            }

            val updated = currentList[index].copy(lastActiveAt = System.currentTimeMillis())
            currentList[index] = updated

            _savedProfiles.value = currentList.sortedByDescending { it.lastActiveAt }
            _activeProfileEmail.value = cleanEmail
            persistRegistryLocked(context)

            Log.i(TAG, "Switched active profile to: $cleanEmail")
            updated
        }
    }

    /**
     * Removes a profile and recursively deletes its isolated disk partition.
     */
    suspend fun removeProfile(
        context: Context,
        email: String
    ) = withContext(Dispatchers.IO) {
        ensureLoaded(context)
        mutex.withLock {
            val cleanEmail = email.trim().lowercase(Locale.ROOT)
            val currentList = _savedProfiles.value.toMutableList()
            val removed = currentList.removeAll { it.email.equals(cleanEmail, ignoreCase = true) }
            if (removed) {
                if (_activeProfileEmail.value.equals(cleanEmail, ignoreCase = true)) {
                    _activeProfileEmail.value = currentList.firstOrNull()?.email
                }
                _savedProfiles.value = currentList
                persistRegistryLocked(context)

                // Delete profile directory recursively
                try {
                    val profileDir = File(context.filesDir, "profiles/${sanitizeEmail(cleanEmail)}")
                    if (profileDir.exists()) {
                        profileDir.deleteRecursively()
                    }
                    Log.i(TAG, "Removed profile and deleted directory for: $cleanEmail")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed deleting profile directory: ${e.message}")
                }
            }
        }
    }

    /**
     * Synchronous lookup of current active profile.
     */
    fun getActiveProfile(): SavedAccountProfile? {
        val email = _activeProfileEmail.value ?: return null
        return _savedProfiles.value.firstOrNull { it.email.equals(email, ignoreCase = true) }
    }
}
