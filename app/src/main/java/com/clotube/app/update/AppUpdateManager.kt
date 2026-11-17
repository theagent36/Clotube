package com.clotube.app.update

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.annotation.Keep
import androidx.core.content.FileProvider
import com.clotube.app.BuildConfig
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.Serializable
import java.util.concurrent.TimeUnit

/**
 * Data structure holding parsed release details from GitHub.
 */
@Keep
data class AppUpdateInfo(
    val tagName: String,
    val releaseName: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val apkSize: Long,
    val fileName: String,
    val currentVersion: String,
    val releaseUrl: String
) : Serializable

/**
 * Observable UI state machine for update checks, downloads, and installation.
 */
sealed class AppUpdateState {
    object Idle : AppUpdateState()
    object Checking : AppUpdateState()
    data class UpdateAvailable(val info: AppUpdateInfo) : AppUpdateState()
    data class UpToDate(val currentVersion: String) : AppUpdateState()
    data class Downloading(val progress: Float, val bytesDownloaded: Long, val totalBytes: Long) : AppUpdateState()
    data class Downloaded(val info: AppUpdateInfo, val apkFile: File) : AppUpdateState()
    data class Error(val message: String) : AppUpdateState()
}

/**
 * Manages in-app GitHub releases checking, resumable APK downloading,
 * release notes viewing ("What's New"), and FileProvider-based APK package installer launch.
 */
object AppUpdateManager {

    private const val TAG = "AppUpdateManager"
    private const val GITHUB_REPO_API = "https://api.github.com/repos/theagent36/Clotube/releases/latest"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val _updateState = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val updateState: StateFlow<AppUpdateState> = _updateState.asStateFlow()

    @Volatile
    private var cachedReleaseNotes: String? = null

    /**
     * Resets state back to Idle.
     */
    fun resetState() {
        _updateState.value = AppUpdateState.Idle
    }

    /**
     * Compares two semantic version strings (e.g. "3.7" vs "v3.8.0").
     * Returns true if candidateVersion is strictly higher than currentVersion.
     */
    fun isNewerVersion(currentVersion: String, candidateVersion: String): Boolean {
        try {
            val cleanCurrent = currentVersion.trim().removePrefix("v").removePrefix("V").split("-")[0]
            val cleanCandidate = candidateVersion.trim().removePrefix("v").removePrefix("V").split("-")[0]

            val currentParts = cleanCurrent.split(".").map { it.toIntOrNull() ?: 0 }
            val candidateParts = cleanCandidate.split(".").map { it.toIntOrNull() ?: 0 }

            val maxLen = maxOf(currentParts.size, candidateParts.size)
            for (i in 0 until maxLen) {
                val cur = currentParts.getOrElse(i) { 0 }
                val can = candidateParts.getOrElse(i) { 0 }
                if (can > cur) return true
                if (can < cur) return false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to compare versions '$currentVersion' vs '$candidateVersion': ${e.message}")
        }
        return false
    }

    /**
     * Checks GitHub repository releases for a newer version than current [BuildConfig.VERSION_NAME].
     */
    suspend fun checkForUpdates(context: Context, force: Boolean = false): AppUpdateState = withContext(Dispatchers.IO) {
        if (!force && _updateState.value is AppUpdateState.Downloading) {
            return@withContext _updateState.value
        }

        _updateState.value = AppUpdateState.Checking

        val currentVersion = BuildConfig.VERSION_NAME

        val request = Request.Builder()
            .url(GITHUB_REPO_API)
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "Clotube-Android/$currentVersion")
            .get()
            .build()

        try {
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val code = response.code
                val err = if (code == 404) "No releases found on repository" else "GitHub API error: $code"
                Log.w(TAG, err)
                val state = AppUpdateState.Error(err)
                _updateState.value = state
                return@withContext state
            }

            val body = response.body?.string()
            if (body.isNullOrBlank()) {
                val state = AppUpdateState.Error("Empty response from GitHub API")
                _updateState.value = state
                return@withContext state
            }

            val json = JsonParser.parseString(body).asJsonObject
            val tagName = json.get("tag_name")?.asString ?: ""
            val releaseName = json.get("name")?.asString ?: tagName
            val releaseNotes = json.get("body")?.asString ?: "No release notes provided."
            val htmlUrl = json.get("html_url")?.asString ?: "https://github.com/theagent36/Clotube/releases"

            cachedReleaseNotes = releaseNotes

            // Search assets for an APK file
            val assets = json.getAsJsonArray("assets")
            var downloadUrl = ""
            var apkSize = 0L
            var apkName = "Clotube-$tagName.apk"

            if (assets != null) {
                for (elem in assets) {
                    val asset = elem.asJsonObject
                    val name = asset.get("name")?.asString.orEmpty()
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        downloadUrl = asset.get("browser_download_url")?.asString.orEmpty()
                        apkSize = asset.get("size")?.asLong ?: 0L
                        apkName = name
                        break
                    }
                }
            }

            val isNewer = isNewerVersion(currentVersion, tagName)
            if (isNewer && downloadUrl.isNotBlank()) {
                val info = AppUpdateInfo(
                    tagName = tagName,
                    releaseName = releaseName,
                    releaseNotes = releaseNotes,
                    downloadUrl = downloadUrl,
                    apkSize = apkSize,
                    fileName = apkName,
                    currentVersion = currentVersion,
                    releaseUrl = htmlUrl
                )
                val state = AppUpdateState.UpdateAvailable(info)
                _updateState.value = state
                state
            } else {
                val state = AppUpdateState.UpToDate(currentVersion)
                _updateState.value = state
                state
            }
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed: ${e.message}", e)
            val state = AppUpdateState.Error(e.localizedMessage ?: "Network connection error")
            _updateState.value = state
            state
        }
    }

    /**
     * Downloads the APK file into `context.cacheDir/updates/` with progress tracking.
     */
    suspend fun downloadUpdate(
        context: Context,
        info: AppUpdateInfo
    ): Result<File> = withContext(Dispatchers.IO) {
        val updatesDir = File(context.cacheDir, "updates")
        if (!updatesDir.exists()) {
            updatesDir.mkdirs()
        }

        val apkFile = File(updatesDir, info.fileName)
        val tempFile = File(updatesDir, "${info.fileName}.download")

        _updateState.value = AppUpdateState.Downloading(
            progress = 0f,
            bytesDownloaded = 0L,
            totalBytes = info.apkSize
        )

        try {
            val request = Request.Builder()
                .url(info.downloadUrl)
                .header("User-Agent", "Clotube-Android/${info.currentVersion}")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val err = "Download failed with HTTP ${response.code}"
                _updateState.value = AppUpdateState.Error(err)
                return@withContext Result.failure(Exception(err))
            }

            val responseBody = response.body
                ?: return@withContext Result.failure(Exception("Empty download body"))

            val totalBytes = if (responseBody.contentLength() > 0) responseBody.contentLength() else info.apkSize
            var bytesCopied = 0L

            responseBody.byteStream().use { input: InputStream ->
                FileOutputStream(tempFile).use { output: FileOutputStream ->
                    val buffer = ByteArray(8 * 1024)
                    var read: Int
                    var lastEmittedPercent = -1

                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        bytesCopied += read

                        val progress = if (totalBytes > 0) (bytesCopied.toFloat() / totalBytes.toFloat()) else 0f
                        val percent = (progress * 100).toInt()
                        if (percent != lastEmittedPercent) {
                            lastEmittedPercent = percent
                            _updateState.value = AppUpdateState.Downloading(
                                progress = progress.coerceIn(0f, 1f),
                                bytesDownloaded = bytesCopied,
                                totalBytes = totalBytes
                            )
                        }
                    }
                    output.flush()
                }
            }

            if (tempFile.exists()) {
                if (apkFile.exists()) apkFile.delete()
                tempFile.renameTo(apkFile)
            }

            _updateState.value = AppUpdateState.Downloaded(info, apkFile)
            Result.success(apkFile)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}", e)
            if (tempFile.exists()) tempFile.delete()
            val state = AppUpdateState.Error("Download failed: ${e.localizedMessage ?: "Unknown error"}")
            _updateState.value = state
            Result.failure(e)
        }
    }

    /**
     * Launches the system package installer for the given downloaded APK via FileProvider.
     */
    fun installApk(context: Context, apkFile: File): Boolean {
        if (!apkFile.exists() || apkFile.length() <= 0) {
            Log.e(TAG, "Cannot install non-existent or empty APK: ${apkFile.absolutePath}")
            return false
        }

        return try {
            val authority = "${context.packageName}.fileprovider"
            val uri = FileProvider.getUriForFile(context, authority, apkFile)

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(installIntent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch package installer: ${e.message}", e)
            false
        }
    }

    /**
     * Returns cached release notes or fetches them from GitHub if not loaded yet.
     */
    suspend fun getOrFetchReleaseNotes(context: Context): String = withContext(Dispatchers.IO) {
        cachedReleaseNotes?.let { return@withContext it }
        val state = checkForUpdates(context, force = true)
        if (state is AppUpdateState.UpdateAvailable) {
            state.info.releaseNotes
        } else {
            cachedReleaseNotes ?: "Clotube Android\n\n• Multi-profile account switching with isolated local cache\n• Sticky subtitle and audio track persistence\n• Dynamic subtitle font scaling\n• In-app GitHub release updates\n• Obsidian Cinema design system"
        }
    }
}
