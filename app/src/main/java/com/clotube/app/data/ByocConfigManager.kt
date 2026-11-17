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
    @SerializedName("clientId") val clientId: String = "",
    @SerializedName("projectId") val projectId: String = "",
    @SerializedName("importedFileName") val importedFileName: String = "",
    @SerializedName("importedAt") val importedAt: Long = 0L
) : Serializable

/**
 * Manages BYOC (Bring Your Own Cloud) custom OAuth client credentials:
 * parses standard Google Cloud console OAuth JSON files, validates client_id patterns,
 * persists settings atomically to disk, and exposes reactive StateFlow.
 */
object ByocConfigManager {

    private const val TAG = "ByocConfigManager"
    private const val CONFIG_FILE_NAME = "clotube_byoc_oauth.json"

    private val mutex = Mutex()
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _configFlow = MutableStateFlow(ByocOAuthConfig())
    val configFlow: StateFlow<ByocOAuthConfig> = _configFlow.asStateFlow()

    @Volatile
    private var isInitialized = false

    private fun getConfigFile(context: Context): File =
        File(context.filesDir, CONFIG_FILE_NAME)

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
     * Loads the BYOC configuration from disk into memory.
     */
    suspend fun ensureLoaded(context: Context) = withContext(Dispatchers.IO) {
        if (isInitialized) return@withContext
        mutex.withLock {
            if (isInitialized) return@withLock
            val file = getConfigFile(context)
            if (file.exists() && file.length() > 0) {
                try {
                    val json = file.readText(Charsets.UTF_8)
                    val parsed = gson.fromJson(json, ByocOAuthConfig::class.java)
                    if (parsed != null) {
                        _configFlow.value = parsed
                        Log.i(TAG, "Loaded BYOC config: enabled=${parsed.isEnabled}, project=${parsed.projectId}")
                        isInitialized = true
                        return@withLock
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse BYOC config file: ${e.message}")
                }
            }
            _configFlow.value = ByocOAuthConfig()
            isInitialized = true
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
     * rich human-friendly beginner validation.
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
                val clientObj = when {
                    rootJson.has("installed") && rootJson.get("installed").isJsonObject ->
                        rootJson.getAsJsonObject("installed")
                    rootJson.has("web") && rootJson.get("web").isJsonObject ->
                        rootJson.getAsJsonObject("web")
                    rootJson.has("client_id") ->
                        rootJson
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
                val projectId = clientObj.get("project_id")?.asString?.trim().orEmpty()

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
                    clientId = clientId,
                    projectId = projectId.ifBlank { "custom-project" },
                    importedFileName = fileName.ifBlank { "client_secret.json" },
                    importedAt = System.currentTimeMillis()
                )

                // Atomic write to disk
                val file = getConfigFile(context)
                val tempFile = File(context.filesDir, "$CONFIG_FILE_NAME.tmp")
                val serialized = gson.toJson(newConfig)
                tempFile.writeText(serialized, Charsets.UTF_8)
                if (!tempFile.renameTo(file)) {
                    file.writeText(serialized, Charsets.UTF_8)
                    tempFile.delete()
                }

                _configFlow.value = newConfig
                Log.i(TAG, "Successfully imported BYOC OAuth config: project=${newConfig.projectId}")
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
     * Clears custom BYOC OAuth configuration and reverts to default app credentials.
     */
    suspend fun clearByocConfig(context: Context) = withContext(Dispatchers.IO) {
        ensureLoaded(context)
        mutex.withLock {
            val emptyConfig = ByocOAuthConfig(isEnabled = false)
            val file = getConfigFile(context)
            if (file.exists()) {
                file.delete()
            }
            _configFlow.value = emptyConfig
            Log.i(TAG, "Cleared BYOC OAuth config. Reverted to Clotube defaults.")
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
