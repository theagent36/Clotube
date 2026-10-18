package com.clotube.app.data

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.clotube.app.auth.GoogleAuthRepository
import com.clotube.app.model.Episode
import com.clotube.app.model.ExternalSubtitleTrack
import com.clotube.app.model.GenreResolver
import com.clotube.app.model.Season
import com.clotube.app.model.TvSeries
import com.clotube.app.model.VideoItem
import com.clotube.app.player.DriveMediaSourceFactory
import com.clotube.app.viewmodel.formatTimestamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.concurrent.TimeUnit

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "clotube_drive_prefs")

data class DriveFoldersStatus(
    val moviesFolderId: String?,
    val seriesFolderId: String?,
    val isMoviesMissing: Boolean,
    val isSeriesMissing: Boolean,
    val needsOnboarding: Boolean
)

data class ShowFolder(
    val id: String,
    val name: String
)

/**
 * First-launch Google Drive folder manager and scoped content query engine.
 * Enforces strict folder isolation and a Fail-Closed Policy:
 * - Only files inside the designated root 'Movies' folder appear under Movies.
 * - Only show folders inside the designated root 'Series' folder appear under Series.
 * - Global searches across Drive are completely eliminated to prevent privacy leaks.
 */
class DriveFolderManager(
    private val context: Context,
    private val authRepository: GoogleAuthRepository,
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        private const val TAG = "DriveFolderManager"
        private const val BASE_DRIVE_API = "https://www.googleapis.com/drive/v3/files"
        const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"

        val KEY_MOVIES_FOLDER_ID = stringPreferencesKey("movies_folder_id")
        val KEY_SERIES_FOLDER_ID = stringPreferencesKey("series_folder_id")
        val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")

        private val EPISODE_REGEX = """(?i)(?:s|season\s*)(\d{1,2})[\s._-]*(?:e|ep|x)?(\d{1,2})""".toRegex()
        private val GENERIC_MOVIE_STEMS = setOf(
            "movie", "video", "main", "film", "cd1", "cd2", "feature", "play", "default", "stream"
        )
        private val SUBTITLE_LANG_REGEX = Regex("""(?i)(?:^|[._\-\[\(])([a-z]{2,3})(?:[\]\)]?)\.(?:srt|vtt|sub|ass|ssa)$""")

        private val COMMON_LANG_MAP = mapOf(
            "en" to "English", "eng" to "English",
            "es" to "Spanish", "spa" to "Spanish",
            "fr" to "French", "fre" to "French", "fra" to "French",
            "de" to "German", "ger" to "German", "deu" to "German",
            "it" to "Italian", "ita" to "Italian",
            "pt" to "Portuguese", "por" to "Portuguese",
            "ru" to "Russian", "rus" to "Russian",
            "ja" to "Japanese", "jpn" to "Japanese",
            "ko" to "Korean", "kor" to "Korean",
            "zh" to "Chinese", "chi" to "Chinese", "zho" to "Chinese",
            "hi" to "Hindi", "hin" to "Hindi",
            "ar" to "Arabic", "ara" to "Arabic",
            "nl" to "Dutch", "dut" to "Dutch", "nld" to "Dutch",
            "pl" to "Polish", "pol" to "Polish",
            "tr" to "Turkish", "tur" to "Turkish",
            "sv" to "Swedish", "swe" to "Swedish",
            "da" to "Danish", "dan" to "Danish",
            "fi" to "Finnish", "fin" to "Finnish",
            "no" to "Norwegian", "nor" to "Norwegian"
        )

        fun isSubtitleFile(fileName: String, mimeType: String? = null): Boolean {
            val lower = fileName.lowercase()
            return lower.endsWith(".srt") ||
                    lower.endsWith(".vtt") ||
                    lower.endsWith(".sub") ||
                    lower.endsWith(".ass") ||
                    lower.endsWith(".ssa") ||
                    (mimeType?.startsWith("text/") == true && !lower.endsWith(".txt"))
        }

        fun resolveSubtitleLanguage(fileName: String): Pair<String, String> {
            val lower = fileName.lowercase()
            val baseWithoutExt = lower.substringBeforeLast('.')

            for ((code, name) in COMMON_LANG_MAP) {
                if (name.lowercase() in baseWithoutExt) {
                    val shortCode = if (code.length == 2) code else Locale.getISOLanguages().find { Locale(it).isO3Language.equals(code, true) } ?: code
                    return Pair(shortCode, "$name (External)")
                }
            }

            val match = SUBTITLE_LANG_REGEX.find(fileName)
            if (match != null) {
                val rawCode = match.groupValues[1].lowercase()
                val mappedName = COMMON_LANG_MAP[rawCode]
                if (mappedName != null) {
                    return Pair(rawCode.take(2), "$mappedName (External)")
                }
                val loc = Locale(rawCode)
                if (loc.displayLanguage.isNotBlank() && loc.displayLanguage != rawCode) {
                    return Pair(rawCode, "${loc.displayLanguage} (External)")
                }
                return Pair(rawCode, "${rawCode.uppercase()} (External)")
            }

            val cleanBase = fileName.substringBeforeLast('.')
            return Pair("und", "$cleanBase (External)")
        }

        fun resolveSubtitleMimeType(fileName: String): String {
            val lower = fileName.lowercase()
            return when {
                lower.endsWith(".vtt") -> "text/vtt"
                lower.endsWith(".ssa") || lower.endsWith(".ass") -> "text/x-ssa"
                lower.endsWith(".srt") || lower.endsWith(".sub") -> "application/x-subrip"
                else -> "application/x-subrip"
            }
        }

        fun cleanMediaTitle(fileName: String): String {
            return fileName
                .replace(Regex("""\.(mkv|mp4|webm|mov|avi)$""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""(?i)[._-](1080p|720p|2160p|4k|60fps|bluray|web-dl|hevc|x265|x264|aac|dts).*"""), "")
                .replace(".", " ")
                .replace("_", " ")
                .trim()
        }

        fun getResolutionLabel(width: Long, height: Long): String {
            return when {
                height >= 2160 || width >= 3840 -> "4K UHD"
                height >= 1440 || width >= 2560 -> "1440p QHD"
                height >= 1080 || width >= 1920 -> "1080p FHD"
                height >= 720 || width >= 1280 -> "720p HD"
                height > 0 -> "${height}p SD"
                else -> "HD"
            }
        }
    }

    val moviesFolderIdFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        val id = prefs[KEY_MOVIES_FOLDER_ID]
        if (id == "root") null else id
    }

    val seriesFolderIdFlow: Flow<String?> = context.dataStore.data.map { prefs ->
        val id = prefs[KEY_SERIES_FOLDER_ID]
        if (id == "root") null else id
    }

    val isOnboardingCompletedFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_ONBOARDING_COMPLETED] ?: false
    }

    /**
     * Retrieves the strictly verified alphanumeric folderId for the root 'Movies' folder.
     * Rejects and purges any instance of "root".
     */
    suspend fun getMoviesFolderId(): String? = withContext(Dispatchers.IO) {
        val prefs = context.dataStore.data.first()
        var moviesId = prefs[KEY_MOVIES_FOLDER_ID]

        // Strict verification: "root" is NEVER a valid folder ID
        if (moviesId == "root") {
            Log.w(TAG, "Privacy check: movies_folder_id was set to 'root'. Purging from DataStore.")
            context.dataStore.edit { it.remove(KEY_MOVIES_FOLDER_ID) }
            moviesId = null
        }

        if (moviesId.isNullOrBlank()) {
            val token = authRepository.getAccessToken().getOrNull() ?: return@withContext null
            moviesId = findRootFolderIdByName("Movies", token)
            if (!moviesId.isNullOrBlank() && moviesId != "root") {
                saveFolderId(KEY_MOVIES_FOLDER_ID, moviesId)
            } else {
                moviesId = null
            }
        }
        moviesId
    }

    /**
     * Retrieves the strictly verified alphanumeric folderId for the root 'Series' folder.
     * Rejects and purges any instance of "root".
     */
    suspend fun getSeriesFolderId(): String? = withContext(Dispatchers.IO) {
        val prefs = context.dataStore.data.first()
        var seriesId = prefs[KEY_SERIES_FOLDER_ID]

        // Strict verification: "root" is NEVER a valid folder ID
        if (seriesId == "root") {
            Log.w(TAG, "Privacy check: series_folder_id was set to 'root'. Purging from DataStore.")
            context.dataStore.edit { it.remove(KEY_SERIES_FOLDER_ID) }
            seriesId = null
        }

        if (seriesId.isNullOrBlank()) {
            val token = authRepository.getAccessToken().getOrNull() ?: return@withContext null
            seriesId = findRootFolderIdByName("Series", token)
            if (!seriesId.isNullOrBlank() && seriesId != "root") {
                saveFolderId(KEY_SERIES_FOLDER_ID, seriesId)
            } else {
                seriesId = null
            }
        }
        seriesId
    }

    /**
     * Inspects Google Drive root and local DataStore for Movies and Series folders.
     */
    suspend fun checkFoldersStatus(): Result<DriveFoldersStatus> = withContext(Dispatchers.IO) {
        val token = authRepository.getAccessToken().getOrNull()
            ?: return@withContext Result.failure(IllegalStateException("User not authenticated"))

        val moviesId = getMoviesFolderId()
        val seriesId = getSeriesFolderId()

        val isMoviesMissing = moviesId.isNullOrBlank() || moviesId == "root"
        val isSeriesMissing = seriesId.isNullOrBlank() || seriesId == "root"
        val needsOnboarding = isMoviesMissing || isSeriesMissing

        Result.success(
            DriveFoldersStatus(
                moviesFolderId = if (!isMoviesMissing) moviesId else null,
                seriesFolderId = if (!isSeriesMissing) seriesId else null,
                isMoviesMissing = isMoviesMissing,
                isSeriesMissing = isSeriesMissing,
                needsOnboarding = needsOnboarding
            )
        )
    }

    /**
     * Creates missing Movies and/or Series root folders in Google Drive and persists their IDs.
     */
    suspend fun createMissingFolders(
        createMovies: Boolean,
        createSeries: Boolean
    ): Result<Pair<String?, String?>> = withContext(Dispatchers.IO) {
        val token = authRepository.getAccessToken().getOrNull()
            ?: return@withContext Result.failure(IllegalStateException("User not authenticated"))

        var newMoviesId: String? = null
        var newSeriesId: String? = null

        if (createMovies) {
            val created = createFolderOnDrive("Movies", token)
            if (created.isSuccess) {
                newMoviesId = created.getOrNull()
                if (!newMoviesId.isNullOrBlank() && newMoviesId != "root") {
                    saveFolderId(KEY_MOVIES_FOLDER_ID, newMoviesId)
                } else {
                    return@withContext Result.failure(Exception("Created Movies folder returned invalid ID: $newMoviesId"))
                }
            } else {
                return@withContext Result.failure(created.exceptionOrNull() ?: Exception("Failed to create Movies folder"))
            }
        }

        if (createSeries) {
            val created = createFolderOnDrive("Series", token)
            if (created.isSuccess) {
                newSeriesId = created.getOrNull()
                if (!newSeriesId.isNullOrBlank() && newSeriesId != "root") {
                    saveFolderId(KEY_SERIES_FOLDER_ID, newSeriesId)
                } else {
                    return@withContext Result.failure(Exception("Created Series folder returned invalid ID: $newSeriesId"))
                }
            } else {
                return@withContext Result.failure(created.exceptionOrNull() ?: Exception("Failed to create Series folder"))
            }
        }

        context.dataStore.edit { it[KEY_ONBOARDING_COMPLETED] = true }
        Result.success(Pair(newMoviesId, newSeriesId))
    }

    /**
     * Fail-Closed Hybrid Scoped Movies Fetcher:
     * Discovers movies stored either as loose video files in the root 'Movies' folder OR inside
     * dedicated subfolders (e.g., "Movies/Inception (2010)/Inception.mkv" along with sidecar captions).
     * If moviesFolderId is null, blank, or 'root', immediately returns emptyList() to prevent privacy leaks.
     */
    suspend fun fetchScopedMovies(): Result<List<VideoItem>> = withContext(Dispatchers.IO) {
        val token = authRepository.getAccessToken().getOrNull()
            ?: return@withContext Result.failure(IllegalStateException("User not authenticated"))

        val moviesFolderId = getMoviesFolderId()
        if (moviesFolderId.isNullOrBlank() || moviesFolderId == "root") {
            Log.w(TAG, "Fail-Closed: moviesFolderId is null, empty, or 'root'. Returning emptyList() to prevent privacy leak.")
            return@withContext Result.success(emptyList()) // Strict privacy: do not fetch anything
        }

        // 1. Query all direct children of verified root Movies folder without mimeType restriction
        val query = "'$moviesFolderId' in parents and trashed = false"
        val fields = "files(id, name, size, mimeType, thumbnailLink, videoMediaMetadata)"

        val url = Uri.parse(BASE_DRIVE_API).buildUpon()
            .appendQueryParameter("q", query)
            .appendQueryParameter("fields", fields)
            .appendQueryParameter("pageSize", "100")
            .appendQueryParameter("supportsAllDrives", "true")
            .appendQueryParameter("includeItemsFromAllDrives", "true")
            .build()
            .toString()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", "Clotube/1.0")
            .get()
            .build()

        try {
            val response = okHttpClient.newCall(request).execute()
            response.use { res ->
                if (!res.isSuccessful) {
                    return@withContext Result.failure(Exception("Movies query failed: ${res.code}"))
                }
                val body = res.body?.string().orEmpty()
                val json = JSONObject(body)
                val files = json.optJSONArray("files") ?: return@withContext Result.success(emptyList())

                val directVideoObjs = mutableListOf<JSONObject>()
                val movieFolderObjs = mutableListOf<JSONObject>()
                val rootSubtitleObjs = mutableListOf<JSONObject>()

                for (i in 0 until files.length()) {
                    val obj = files.getJSONObject(i)
                    val mime = obj.optString("mimeType", "")
                    val name = obj.optString("name", "")
                    when {
                        mime == FOLDER_MIME_TYPE -> movieFolderObjs.add(obj)
                        mime.startsWith("video/") -> directVideoObjs.add(obj)
                        isSubtitleFile(name, mime) -> rootSubtitleObjs.add(obj)
                    }
                }

                val allMovies = mutableListOf<VideoItem>()

                // 2. Process Dedicated Movie Subfolders
                for (folderObj in movieFolderObjs) {
                    val folderId = folderObj.getString("id")
                    val folderName = folderObj.getString("name")
                    val movieItem = fetchMovieFromSubfolder(folderId, folderName, token)
                    if (movieItem != null) {
                        allMovies.add(movieItem)
                    }
                }

                // 3. Process Direct Video Files in Root Movies Folder
                for (videoObj in directVideoObjs) {
                    val videoItem = parseVideoItem(videoObj)
                    val rawName = videoObj.optString("name", videoItem.title)
                    val movieMeta = com.clotube.app.data.metadata.OnlineMetadataResolver.resolveMovie(rawName)
                    val finalThumb = movieMeta.posterUrl.ifBlank { videoItem.thumbnailUrl }
                    val videoStem = rawName.substringBeforeLast('.')
                    val matchingSubs = rootSubtitleObjs.filter { subObj ->
                        val subName = subObj.optString("name", "")
                        subName.startsWith(videoStem, ignoreCase = true)
                    }.map { subObj ->
                        val subId = subObj.getString("id")
                        val subName = subObj.optString("name", "subtitles.srt")
                        val (langCode, label) = resolveSubtitleLanguage(subName)
                        val subMime = resolveSubtitleMimeType(subName)
                        val streamUri = DriveMediaSourceFactory.buildDriveStreamUri(subId).toString()
                        ExternalSubtitleTrack(
                            fileId = subId,
                            fileName = subName,
                            languageCode = langCode,
                            label = label,
                            mimeType = subMime,
                            streamUri = streamUri
                        )
                    }
                    allMovies.add(
                        videoItem.copy(
                            displayTitle = movieMeta.officialTitle,
                            thumbnailUrl = finalThumb,
                            externalSubtitles = matchingSubs,
                            genreFormatted = movieMeta.genre
                        )
                    )
                }

                Log.d(TAG, "Fetched ${allMovies.size} total scoped movies (hybrid folders + loose files) from folder '$moviesFolderId'")
                Result.success(allMovies)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching scoped movies", e)
            Result.failure(e)
        }
    }

    /**
     * Inspects a movie subfolder, selects the primary video file (largest file),
     * and discovers sidecar subtitles in the folder and nested 'subs'/'subtitles' directories.
     */
    private suspend fun fetchMovieFromSubfolder(folderId: String, folderName: String, token: String): VideoItem? {
        val query = "'$folderId' in parents and trashed = false"
        val fields = "files(id, name, size, mimeType, thumbnailLink, videoMediaMetadata)"

        val url = Uri.parse(BASE_DRIVE_API).buildUpon()
            .appendQueryParameter("q", query)
            .appendQueryParameter("fields", fields)
            .appendQueryParameter("pageSize", "100")
            .appendQueryParameter("supportsAllDrives", "true")
            .appendQueryParameter("includeItemsFromAllDrives", "true")
            .build()
            .toString()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", "Clotube/1.0")
            .get()
            .build()

        return try {
            val response = okHttpClient.newCall(request).execute()
            response.use { res ->
                if (!res.isSuccessful) return null
                val body = res.body?.string().orEmpty()
                val json = JSONObject(body)
                val files = json.optJSONArray("files") ?: return null

                val videoObjs = mutableListOf<JSONObject>()
                val imageObjs = mutableListOf<JSONObject>()
                val subtitleObjs = mutableListOf<JSONObject>()
                var subsFolderId: String? = null

                for (i in 0 until files.length()) {
                    val obj = files.getJSONObject(i)
                    val mime = obj.optString("mimeType", "")
                    val name = obj.optString("name", "")
                    when {
                        mime.startsWith("video/") -> videoObjs.add(obj)
                        mime.startsWith("image/") -> imageObjs.add(obj)
                        isSubtitleFile(name, mime) -> subtitleObjs.add(obj)
                        mime == FOLDER_MIME_TYPE && (name.equals("subs", ignoreCase = true) || name.equals("subtitles", ignoreCase = true)) -> {
                            subsFolderId = obj.getString("id")
                        }
                    }
                }

                // If no video files directly inside folder, this is not a valid movie subfolder
                if (videoObjs.isEmpty()) return null

                // Pick primary video: largest video file by size
                val primaryVideoObj = videoObjs.maxByOrNull { it.optLong("size", 0L) } ?: videoObjs.first()
                val parsedItem = parseVideoItem(primaryVideoObj)

                // Query nested 'subs' or 'subtitles' directory if present
                if (!subsFolderId.isNullOrBlank()) {
                    val nestedSubs = fetchSubtitlesFromFolder(subsFolderId, token)
                    subtitleObjs.addAll(nestedSubs)
                }

                // Build external subtitle tracks
                val externalSubtitleTracks = subtitleObjs.map { subObj ->
                    val subId = subObj.getString("id")
                    val subName = subObj.optString("name", "subtitles.srt")
                    val (langCode, label) = resolveSubtitleLanguage(subName)
                    val mimeType = resolveSubtitleMimeType(subName)
                    val streamUri = DriveMediaSourceFactory.buildDriveStreamUri(subId).toString()
                    ExternalSubtitleTrack(
                        fileId = subId,
                        fileName = subName,
                        languageCode = langCode,
                        label = label,
                        mimeType = mimeType,
                        streamUri = streamUri
                    )
                }

                // Resolve title: if video filename stem is generic (e.g. movie.mkv, main.mp4), fallback to folder name
                val rawVideoName = primaryVideoObj.optString("name", "")
                val videoStem = rawVideoName.substringBeforeLast('.')
                val isGeneric = videoStem.isBlank() || GENERIC_MOVIE_STEMS.contains(videoStem.lowercase().trim())
                val finalTitle = if (isGeneric || folderName.isNotBlank()) folderName else cleanMediaTitle(rawVideoName)

                // Detect local poster/backdrop images inside subfolder
                val preferredImg = imageObjs.find { img ->
                    val iname = img.optString("name", "").lowercase()
                    iname.contains("poster") || iname.contains("cover") || iname.contains("backdrop") || iname.contains("fanart") || iname.contains("folder")
                } ?: imageObjs.firstOrNull()

                val localImgThumb = preferredImg?.optString("thumbnailLink")?.replace("=s220", "=s1200")

                // Resolve pristine OTT movie metadata via OnlineMetadataResolver with local/drive fallback
                val movieMeta = com.clotube.app.data.metadata.OnlineMetadataResolver.resolveMovie(finalTitle)
                val finalThumb = movieMeta.posterUrl.ifBlank { localImgThumb ?: parsedItem.thumbnailUrl }

                parsedItem.copy(
                    displayTitle = movieMeta.officialTitle,
                    thumbnailUrl = finalThumb,
                    externalSubtitles = externalSubtitleTracks,
                    genreFormatted = movieMeta.genre
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching movie subfolder '$folderName'", e)
            null
        }
    }

    private fun fetchSubtitlesFromFolder(folderId: String, token: String): List<JSONObject> {
        val query = "'$folderId' in parents and trashed = false"
        val fields = "files(id, name, mimeType)"

        val url = Uri.parse(BASE_DRIVE_API).buildUpon()
            .appendQueryParameter("q", query)
            .appendQueryParameter("fields", fields)
            .appendQueryParameter("pageSize", "50")
            .appendQueryParameter("supportsAllDrives", "true")
            .appendQueryParameter("includeItemsFromAllDrives", "true")
            .build()
            .toString()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", "Clotube/1.0")
            .get()
            .build()

        return try {
            val response = okHttpClient.newCall(request).execute()
            response.use { res ->
                if (!res.isSuccessful) return emptyList()
                val body = res.body?.string().orEmpty()
                val json = JSONObject(body)
                val files = json.optJSONArray("files") ?: return emptyList()

                val subs = mutableListOf<JSONObject>()
                for (i in 0 until files.length()) {
                    val obj = files.getJSONObject(i)
                    val name = obj.optString("name", "")
                    val mime = obj.optString("mimeType", "")
                    if (isSubtitleFile(name, mime)) {
                        subs.add(obj)
                    }
                }
                subs
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching nested subtitles from folder $folderId", e)
            emptyList()
        }
    }

    /**
     * Fail-Closed Scoped Series Fetcher:
     * Queries subfolders inside the Series folder (representing individual TV shows)
     * and parses multi-season episodes inside each show.
     * If seriesFolderId is null, blank, or 'root', immediately returns emptyList().
     */
    suspend fun fetchScopedSeries(): Result<List<TvSeries>> = withContext(Dispatchers.IO) {
        val token = authRepository.getAccessToken().getOrNull()
            ?: return@withContext Result.failure(IllegalStateException("User not authenticated"))

        val seriesFolderId = getSeriesFolderId()
        if (seriesFolderId.isNullOrBlank() || seriesFolderId == "root") {
            Log.w(TAG, "Fail-Closed: seriesFolderId is null, empty, or 'root'. Returning emptyList() to prevent privacy leak.")
            return@withContext Result.success(emptyList()) // Strict privacy: do not fetch anything
        }

        // 1. Query subfolders inside Series folder (each subfolder is a TV show)
        val query = "'$seriesFolderId' in parents and mimeType = '$FOLDER_MIME_TYPE' and trashed = false"
        val fields = "files(id, name, thumbnailLink)"

        val url = Uri.parse(BASE_DRIVE_API).buildUpon()
            .appendQueryParameter("q", query)
            .appendQueryParameter("fields", fields)
            .appendQueryParameter("pageSize", "50")
            .appendQueryParameter("supportsAllDrives", "true")
            .appendQueryParameter("includeItemsFromAllDrives", "true")
            .build()
            .toString()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .header("User-Agent", "Clotube/1.0")
            .get()
            .build()

        try {
            val response = okHttpClient.newCall(request).execute()
            val showFolders = mutableListOf<ShowFolder>()
            response.use { res ->
                if (!res.isSuccessful) {
                    return@withContext Result.failure(Exception("Series folder query failed: ${res.code}"))
                }
                val body = res.body?.string().orEmpty()
                val json = JSONObject(body)
                val files = json.optJSONArray("files") ?: return@withContext Result.success(emptyList())

                for (i in 0 until files.length()) {
                    val obj = files.getJSONObject(i)
                    showFolders.add(
                        ShowFolder(
                            id = obj.getString("id"),
                            name = obj.getString("name")
                        )
                    )
                }
            }

            // 2. For each show folder, fetch video files & season subfolders using strict 2-level hierarchy
            val seriesRepo = SeriesRepository(authRepository, okHttpClient)
            val tvSeriesList = mutableListOf<TvSeries>()
            for (show in showFolders) {
                val series = seriesRepo.getShowDetails(show.id, show.name)
                if (series.seasons.isNotEmpty()) {
                    tvSeriesList.add(series)
                }
            }

            Log.d(TAG, "Fetched ${tvSeriesList.size} scoped TV series from folder '$seriesFolderId'")
            Result.success(tvSeriesList)
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching scoped series", e)
            Result.failure(e)
        }
    }

    private fun cleanVideoTitle(raw: String): String {
        var title = raw
        title = title.replace(Regex("""\.(mkv|mp4|webm|avi|mov|ts|m4v)$""", RegexOption.IGNORE_CASE), "")
        title = title.replace(Regex("""(?i)[._ -]*(1080p|720p|2160p|4k|60fps|bluray|web-dl|webrip|hevc|x265|x264|aac|dts|remux|h264|hdr|uhd).*"""), "")
        title = title.replace('.', ' ').replace('_', ' ').trim()
        return title.ifBlank { raw }
    }

    private fun parseVideoItem(obj: JSONObject): VideoItem {
        val id = obj.getString("id")
        val name = obj.optString("name", "Untitled Video")
        val sizeBytes = obj.optLong("size", 0L)
        val mimeType = obj.optString("mimeType", "video/mp4")
        val rawThumbnail = if (obj.has("thumbnailLink")) obj.optString("thumbnailLink") else null
        val thumbnail = rawThumbnail?.replace("=s220", "=s800") ?: ""

        val videoMeta = obj.optJSONObject("videoMediaMetadata")
        val width = videoMeta?.optInt("width", 0) ?: 0
        val height = videoMeta?.optInt("height", 0) ?: 0
        val durationMillis = videoMeta?.optLong("durationMillis", 0L) ?: 0L

        val rawResolution = getResolutionLabel(width.toLong(), height.toLong())
        val resolutionBadge = when {
            height >= 2160 || width >= 3840 || rawResolution.contains("4K", ignoreCase = true) || rawResolution.contains("2160", ignoreCase = true) -> "4K UHD"
            height >= 1440 || width >= 2560 || rawResolution.contains("1440", ignoreCase = true) -> "1440p QHD"
            height >= 1080 || width >= 1920 || rawResolution.contains("1080", ignoreCase = true) || rawResolution.contains("FHD", ignoreCase = true) -> "1080p FHD"
            height >= 720 || width >= 1280 || rawResolution.contains("720", ignoreCase = true) -> "720p HD"
            height > 0 -> "${height}p"
            rawResolution.isNotBlank() && rawResolution != "HD" -> rawResolution
            else -> "1080p FHD"
        }

        val durationFormatted = if (durationMillis > 0) formatTimestamp(durationMillis) else "00:00"
        val sizeFormatted = formatFileSize(sizeBytes)
        val hdrBadge = if (width >= 3840 || height >= 2160) "HDR10" else null

        val bitrateFormatted = if (durationMillis > 0 && sizeBytes > 0) {
            val durationSec = durationMillis / 1000.0
            val mbps = (sizeBytes * 8.0) / (durationSec * 1_000_000.0)
            String.format(Locale.US, "%.1f Mbps", mbps)
        } else ""

        val codec = when {
            mimeType.contains("matroska", ignoreCase = true) || name.endsWith(".mkv", ignoreCase = true) -> "MKV Container"
            mimeType.contains("mp4", ignoreCase = true) || name.endsWith(".mp4", ignoreCase = true) -> "MP4 Container"
            else -> "Direct Stream"
        }

        val genre = GenreResolver.resolveGenre(name, isSeries = false)
        val cleanTitle = cleanVideoTitle(name)
        val streamUri = DriveMediaSourceFactory.buildDriveStreamUri(id).toString()

        return VideoItem(
            id = id,
            displayTitle = cleanTitle,
            durationFormatted = durationFormatted,
            fileSizeFormatted = sizeFormatted,
            genreFormatted = genre,
            resolutionBadge = resolutionBadge,
            streamUri = streamUri,
            thumbnailUrl = thumbnail,
            fpsBadge = "",
            mimeType = mimeType,
            bitrateFormatted = bitrateFormatted,
            codec = codec,
            audioProfile = "",
            hdrBadge = hdrBadge,
            width = width,
            height = height,
            durationMs = durationMillis,
            sizeBytes = sizeBytes
        )
    }

    /**
     * Strictly queries explicit root folders:
     * `'root' in parents and name = '$folderName' and mimeType = 'application/vnd.google-apps.folder' and trashed = false`
     * Rejects any invalid or "root" ID.
     */
    private fun findRootFolderIdByName(folderName: String, accessToken: String): String? {
        val query = "'root' in parents and name = '$folderName' and mimeType = '$FOLDER_MIME_TYPE' and trashed = false"
        val url = Uri.parse(BASE_DRIVE_API).buildUpon()
            .appendQueryParameter("q", query)
            .appendQueryParameter("fields", "files(id, name)")
            .appendQueryParameter("pageSize", "1")
            .appendQueryParameter("supportsAllDrives", "true")
            .appendQueryParameter("includeItemsFromAllDrives", "true")
            .build()
            .toString()

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("User-Agent", "Clotube/1.0")
            .get()
            .build()

        return try {
            val response = okHttpClient.newCall(request).execute()
            response.use { res ->
                if (!res.isSuccessful) return null
                val body = res.body?.string().orEmpty()
                val json = JSONObject(body)
                val files = json.optJSONArray("files") ?: return null
                if (files.length() > 0) {
                    val foundId = files.getJSONObject(0).getString("id")
                    // Reject "root" and blank IDs
                    if (foundId.isNotBlank() && foundId != "root") {
                        foundId
                    } else null
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed finding root folder '$folderName'", e)
            null
        }
    }

    private fun createFolderOnDrive(folderName: String, accessToken: String): Result<String> {
        val url = "$BASE_DRIVE_API?supportsAllDrives=true"
        val jsonPayload = JSONObject().apply {
            put("name", folderName)
            put("mimeType", FOLDER_MIME_TYPE)
            put("parents", JSONArray().apply { put("root") })
        }

        val requestBody = jsonPayload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $accessToken")
            .header("User-Agent", "Clotube/1.0")
            .post(requestBody)
            .build()

        return try {
            val response = okHttpClient.newCall(request).execute()
            response.use { res ->
                if (!res.isSuccessful) {
                    val error = res.body?.string().orEmpty()
                    return Result.failure(Exception("Failed creating folder '$folderName': ${res.code} - $error"))
                }
                val body = res.body?.string().orEmpty()
                val json = JSONObject(body)
                val createdId = json.getString("id")
                if (createdId.isBlank() || createdId == "root") {
                    return Result.failure(Exception("Created folder returned invalid ID: $createdId"))
                }
                Log.d(TAG, "Successfully created root folder '$folderName' with id $createdId")
                Result.success(createdId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Network exception creating folder '$folderName'", e)
            Result.failure(e)
        }
    }

    suspend fun clearSavedFolderIds() = withContext(Dispatchers.IO) {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_MOVIES_FOLDER_ID)
            prefs.remove(KEY_SERIES_FOLDER_ID)
        }
    }

    suspend fun saveCustomMoviesFolderId(folderId: String) = withContext(Dispatchers.IO) {
        saveFolderId(KEY_MOVIES_FOLDER_ID, folderId)
    }

    suspend fun saveCustomSeriesFolderId(folderId: String) = withContext(Dispatchers.IO) {
        saveFolderId(KEY_SERIES_FOLDER_ID, folderId)
    }

    private suspend fun saveFolderId(key: Preferences.Key<String>, id: String) {
        if (id.isBlank() || id == "root") return
        context.dataStore.edit { prefs ->
            prefs[key] = id
        }
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0L) return "Unknown Size"
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) {
            String.format(Locale.US, "%.1f GB", gb)
        } else {
            val mb = bytes / (1024.0 * 1024.0)
            String.format(Locale.US, "%.1f MB", mb)
        }
    }
}
