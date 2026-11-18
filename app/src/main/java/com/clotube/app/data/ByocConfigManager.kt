package com.clotube.app.data

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.util.Log
import androidx.annotation.Keep
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.Serializable
import java.security.MessageDigest
import java.util.Locale

/**
 * Persisted configuration for Bring Your Own Cloud (BYOC) Custom Google Cloud OAuth.
 */
@Keep
data class ByocOAuthConfig(
    @SerializedName("isEnabled") val isEnabled: Boolean = false,
    @SerializedName("clientType") val clientType: String = "installed",
    @SerializedName("clientId") val clientId: String = "",
    @SerializedName("clientSecret") val clientSecret: String = "",
    @SerializedName("projectId") val projectId: String = "",
    @SerializedName("authUri") val authUri: String = "https://accounts.google.com/o/oauth2/v2/auth",
    @SerializedName("tokenUri") val tokenUri: String = "https://oauth2.googleapis.com/token",
    @SerializedName("importedFileName") val importedFileName: String = "",
    @SerializedName("importedAt") val importedAt: Long = 0L
) : Serializable

/**
 * Persisted OAuth 2.0 session tokens for the active BYOC user session.
 */
@Keep
data class ByocSessionTokens(
    @SerializedName("accessToken") val accessToken: String = "",
    @SerializedName("refreshToken") val refreshToken: String = "",
    @SerializedName("expiresAtMs") val expiresAtMs: Long = 0L,
    @SerializedName("userEmail") val userEmail: String = "",
    @SerializedName("userName") val userName: String = "",
    @SerializedName("userAvatarUrl") val userAvatarUrl: String = ""
) : Serializable

/**
 * Manages BYOC (Bring Your Own Cloud) custom OAuth client credentials & session tokens:
 * parses standard Google Cloud console OAuth JSON files, validates client credentials,
 * persists settings and active session tokens atomically to disk, and exposes reactive StateFlows.
 */
object ByocConfigManager {

    private const val TAG = "ByocConfigManager"
    private const val CONFIG_FILE_PRIMARY = "byoc_oauth_config.json"
    private const val CONFIG_FILE_LEGACY = "clotube_byoc_oauth.json"
    private const val SESSION_FILE_NAME = "byoc_session.json"

    private val mutex = Mutex()
    private val sessionMutex = Mutex()
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _configFlow = MutableStateFlow(ByocOAuthConfig())
    val configFlow: StateFlow<ByocOAuthConfig> = _configFlow.asStateFlow()

    private val _sessionFlow = MutableStateFlow<ByocSessionTokens?>(null)
    val sessionFlow: StateFlow<ByocSessionTokens?> = _sessionFlow.asStateFlow()

    @Volatile
    private var isInitialized = false

    private fun getConfigFile(context: Context): File {
        val primary = File(context.filesDir, CONFIG_FILE_PRIMARY)
        if (primary.exists()) return primary
        val legacy = File(context.filesDir, CONFIG_FILE_LEGACY)
        if (legacy.exists()) return legacy
        return primary
    }

    private fun getSessionFile(context: Context): File =
        File(context.filesDir, SESSION_FILE_NAME)

    /**
     * Inspects package signing certificates and computes runtime SHA-1 fingerprint formatted
     * as colon-separated uppercase hex (e.g. AA:BB:CC:...); returns "Unavailable" safely on any error.
     */
    fun getAppSha1Fingerprint(context: Context): String {
        return try {
            val pm = context.packageManager
            val packageName = context.packageName
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = packageInfo.signingInfo
                if (signingInfo != null) {
                    if (signingInfo.hasMultipleSigners()) {
                        signingInfo.apkContentsSigners
                    } else {
                        signingInfo.signingCertificateHistory
                    }
                } else null
            } else {
                @Suppress("DEPRECATION")
                val packageInfo = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                packageInfo.signatures
            }

            val cert = signatures?.firstOrNull()?.toByteArray() ?: return "Unavailable"
            val md = MessageDigest.getInstance("SHA-1")
            val digest = md.digest(cert)
            digest.joinToString(":") { String.format(Locale.US, "%02X", it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get SHA-1 fingerprint: ${e.message}")
            "Unavailable"
        }
    }

    /**
     * Checks synchronously whether BYOC OAuth has been configured and enabled.
     */
    fun isByocConfigured(context: Context): Boolean {
        val current = _configFlow.value
        if (current.isEnabled && current.clientId.isNotBlank()) return true
        if (!isInitialized) {
            val file = getConfigFile(context)
            if (file.exists() && file.length() > 0) {
                return try {
                    val json = file.readText(Charsets.UTF_8)
                    val parsed = gson.fromJson(json, ByocOAuthConfig::class.java)
                    parsed != null && parsed.isEnabled && parsed.clientId.isNotBlank()
                } catch (_: Exception) {
                    false
                }
            }
        }
        return false
    }

    /**
     * Checks synchronously whether an active, valid BYOC session exists on the device.
     */
    fun hasActiveByocSession(context: Context): Boolean {
        if (!isByocConfigured(context)) return false
        val session = _sessionFlow.value
        if (session != null && (session.refreshToken.isNotBlank() || session.accessToken.isNotBlank())) {
            return true
        }
        val file = getSessionFile(context)
        if (file.exists() && file.length() > 0) {
            return try {
                val json = file.readText(Charsets.UTF_8)
                val parsed = gson.fromJson(json, ByocSessionTokens::class.java)
                parsed != null && (parsed.refreshToken.isNotBlank() || parsed.accessToken.isNotBlank())
            } catch (_: Exception) {
                false
            }
        }
        return false
    }

    /**
     * Loads the BYOC configuration and session tokens from disk into memory.
     */
    suspend fun ensureLoaded(context: Context) = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext
        mutex.withLock {
            if (isInitialized) return@withLock
            // 1. Load OAuth configuration
            val file = getConfigFile(context)
            if (file.exists() && file.length() > 0) {
                try {
                    val json = file.readText(Charsets.UTF_8)
                    val parsed = gson.fromJson(json, ByocOAuthConfig::class.java)
                    if (parsed != null) {
                        _configFlow.value = parsed
                        Log.i(TAG, "Loaded BYOC config: enabled=${parsed.isEnabled}, project=${parsed.projectId}, type=${parsed.clientType}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse BYOC config file: ${e.message}")
                }
            }

            // 2. Load Session tokens
            val sessionFile = getSessionFile(context)
            if (sessionFile.exists() && sessionFile.length() > 0) {
                try {
                    val sessionJson = sessionFile.readText(Charsets.UTF_8)
                    val parsedSession = gson.fromJson(sessionJson, ByocSessionTokens::class.java)
                    if (parsedSession != null) {
                        _sessionFlow.value = parsedSession
                        Log.i(TAG, "Loaded BYOC session for user: ${parsedSession.userEmail}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse BYOC session file: ${e.message}")
                }
            }

            isInitialized = true
        }
    }

    /**
     * Saves active BYOC session tokens atomically to disk and updates reactive StateFlow.
     */
    suspend fun saveSession(context: Context, session: ByocSessionTokens) = withContext(Dispatchers.IO) {
        sessionMutex.withLock {
            try {
                val file = getSessionFile(context)
                val tempFile = File(context.filesDir, "$SESSION_FILE_NAME.tmp")
                val serialized = gson.toJson(session)
                tempFile.writeText(serialized, Charsets.UTF_8)
                if (!tempFile.renameTo(file)) {
                    file.writeText(serialized, Charsets.UTF_8)
                    tempFile.delete()
                }
                _sessionFlow.value = session
                Log.i(TAG, "Successfully saved BYOC session for user: ${session.userEmail}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save BYOC session: ${e.message}", e)
            }
        }
    }

    /**
     * Retrieves the current BYOC session tokens from memory or disk.
     */
    suspend fun getSession(context: Context): ByocSessionTokens? = withContext(Dispatchers.IO) {
        ensureLoaded(context)
        _sessionFlow.value ?: sessionMutex.withLock {
            val file = getSessionFile(context)
            if (file.exists() && file.length() > 0) {
                try {
                    val json = file.readText(Charsets.UTF_8)
                    val parsed = gson.fromJson(json, ByocSessionTokens::class.java)
                    if (parsed != null) {
                        _sessionFlow.value = parsed
                        return@withLock parsed
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to read BYOC session file: ${e.message}")
                }
            }
            null
        }
    }

    /**
     * Clears active BYOC session tokens from memory and disk while preserving client configuration.
     */
    suspend fun clearSession(context: Context) = withContext(Dispatchers.IO) {
        sessionMutex.withLock {
            val file = getSessionFile(context)
            if (file.exists()) {
                file.delete()
            }
            _sessionFlow.value = null
            Log.i(TAG, "Cleared BYOC active session tokens")
        }
    }

    /**
     * Convenience alias for importing custom OAuth credentials directly from a Content Uri.
     */
    suspend fun importFromUri(
        context: Context,
        uri: Uri,
        fileName: String = queryFileName(context, uri) ?: "client_secret.json"
    ): Result<ByocOAuthConfig> = importOAuthJson(context, uri, fileName)

    /**
     * Parses and imports Google Cloud Console OAuth JSON from a content Uri with
     * rich human-friendly validation and extracts full client credentials.
     */
    suspend fun importOAuthJson(
        context: Context,
        uri: Uri,
        fileName: String
    ): Result<ByocOAuthConfig> = withContext(Dispatchers.IO) {
        ensureLoaded(context)
        mutex.withLock {
            try {
                val jsonString = context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.reader(Charsets.UTF_8).readText()
                } ?: return@withContext Result.failure(
                    IllegalArgumentException("Cannot read the selected file. Please make sure the file exists and is accessible.")
                )

                if (jsonString.isBlank()) {
                    return@withContext Result.failure(
                        IllegalArgumentException("The selected JSON file is empty. Please select a valid client_secret_*.json file.")
                    )
                }

                val rootJson = try {
                    JsonParser.parseString(jsonString).asJsonObject
                } catch (e: Exception) {
                    return@withContext Result.failure(
                        IllegalArgumentException("The selected file is not a valid JSON document: ${e.localizedMessage ?: "Parse error"}")
                    )
                }

                // 1. Detect if the user mistakenly selected Firebase google-services.json
                if ((rootJson.has("project_info") || rootJson.has("client")) && !rootJson.has("installed") && !rootJson.has("web")) {
                    return@withContext Result.failure(
                        IllegalArgumentException(
                            "That looks like a Firebase google-services.json file. Please download the OAuth Client JSON (client_secret_*.json) from Google Auth Platform → Clients."
                        )
                    )
                }

                // 2. Resolve client object from "installed", "web", or flat schema
                val isWeb = rootJson.has("web") && rootJson.get("web").isJsonObject
                val isInstalled = rootJson.has("installed") && rootJson.get("installed").isJsonObject

                val clientType = when {
                    isInstalled -> "installed"
                    isWeb -> "web"
                    else -> "installed"
                }

                val clientObj = when {
                    isInstalled -> rootJson.getAsJsonObject("installed")
                    isWeb -> rootJson.getAsJsonObject("web")
                    rootJson.has("client_id") -> rootJson
                    else -> null
                }

                if (clientObj == null) {
                    return@withContext Result.failure(
                        IllegalArgumentException(
                            "Invalid Google Cloud OAuth JSON: Missing 'installed' or 'web' configuration object. When creating the client ID in Google Cloud Console, select 'Desktop app' (or 'Web application') and click Download JSON."
                        )
                    )
                }

                val clientId = clientObj.get("client_id")?.asString?.trim().orEmpty()
                val clientSecret = clientObj.get("client_secret")?.asString?.trim().orEmpty()
                val projectId = clientObj.get("project_id")?.asString?.trim().orEmpty()
                val authUri = clientObj.get("auth_uri")?.asString?.trim()?.ifBlank { null }
                    ?: "https://accounts.google.com/o/oauth2/v2/auth"
                val tokenUri = clientObj.get("token_uri")?.asString?.trim()?.ifBlank { null }
                    ?: "https://oauth2.googleapis.com/token"

                if (clientId.isBlank()) {
                    return@withContext Result.failure(
                        IllegalArgumentException("Invalid JSON: The 'client_id' field is missing or empty inside the configuration object.")
                    )
                }

                if (!clientId.endsWith(".apps.googleusercontent.com")) {
                    return@withContext Result.failure(
                        IllegalArgumentException("Invalid client_id '$clientId'. Expected a Google Cloud OAuth client ID ending with '.apps.googleusercontent.com'.")
                    )
                }

                val newConfig = ByocOAuthConfig(
                    isEnabled = true,
                    clientType = clientType,
                    clientId = clientId,
                    clientSecret = clientSecret,
                    projectId = projectId.ifBlank { "custom-project" },
                    authUri = authUri,
                    tokenUri = tokenUri,
                    importedFileName = fileName.ifBlank { "client_secret.json" },
                    importedAt = System.currentTimeMillis()
                )

                // 3. Atomically write to byoc_oauth_config.json
                val primaryFile = File(context.filesDir, CONFIG_FILE_PRIMARY)
                val tempFile = File(context.filesDir, "$CONFIG_FILE_PRIMARY.tmp")
                val serialized = gson.toJson(newConfig)
                tempFile.writeText(serialized, Charsets.UTF_8)
                if (!tempFile.renameTo(primaryFile)) {
                    primaryFile.writeText(serialized, Charsets.UTF_8)
                    tempFile.delete()
                }

                // Also clean up any legacy config file to prevent drift
                val legacyFile = File(context.filesDir, CONFIG_FILE_LEGACY)
                if (legacyFile.exists()) {
                    legacyFile.delete()
                }

                // 4. Clear any stale session whenever a new client_secret is imported
                clearSession(context)

                _configFlow.value = newConfig
                Log.i(TAG, "Successfully imported BYOC OAuth config: project=${newConfig.projectId}, type=${newConfig.clientType}")
                Result.success(newConfig)
            } catch (e: IllegalArgumentException) {
                Result.failure(e)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to import BYOC OAuth JSON: ${e.message}", e)
                Result.failure(IllegalArgumentException("Failed to read JSON: ${e.localizedMessage ?: "Unknown format"}"))
            }
        }
    }

    /**
     * Clears custom BYOC OAuth configuration and session tokens, reverting to default app credentials.
     */
    suspend fun clearByocConfig(context: Context) = withContext(Dispatchers.IO) {
        ensureLoaded(context)
        mutex.withLock {
            val emptyConfig = ByocOAuthConfig(isEnabled = false)
            val primaryFile = File(context.filesDir, CONFIG_FILE_PRIMARY)
            if (primaryFile.exists()) {
                primaryFile.delete()
            }
            val legacyFile = File(context.filesDir, CONFIG_FILE_LEGACY)
            if (legacyFile.exists()) {
                legacyFile.delete()
            }
            clearSession(context)
            _configFlow.value = emptyConfig
            Log.i(TAG, "Cleared BYOC OAuth config and active session. Reverted to Clotube defaults.")
        }
    }

    /**
     * Queries the human-readable display name from a content Uri.
     */
    fun queryFileName(context: Context, uri: Uri): String? {
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex != -1 && cursor.moveToFirst()) {
                        return cursor.getString(nameIndex)
                    }
                }
            } catch (_: Exception) {}
        }
        return uri.lastPathSegment
    }
}
