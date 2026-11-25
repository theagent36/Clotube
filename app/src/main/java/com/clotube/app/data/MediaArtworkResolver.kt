package com.clotube.app.data

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@androidx.annotation.Keep
data class MediaArtwork(
    val backdropUrl: String? = null,
    val posterUrl: String? = null
)

/**
 * High-performance OTT media artwork resolver for Clotube.
 * Resolves official movie backdrops and posters via Cinemeta's open media catalog,
 * replacing unrepresentative or corrupted Google Drive video frames with pristine 16:9 backdrops.
 */
object MediaArtworkResolver {

    private const val TAG = "MediaArtworkResolver"

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    // In-memory cache to guarantee instant lookups across recompositions and app sessions
    private val artworkCache = ConcurrentHashMap<String, MediaArtwork>()

    /**
     * Resolves official movie backdrop and poster URLs by media title and optional year.
     * Returns cached artwork immediately if previously fetched.
     */
    suspend fun resolveMovieArtwork(
        rawTitle: String,
        fallbackThumb: String? = null
    ): MediaArtwork = withContext(Dispatchers.IO) {
        val cleanTitle = cleanSearchTitle(rawTitle)
        val validFallback = fallbackThumb?.takeIf { !com.clotube.app.data.metadata.OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
        if (cleanTitle.isBlank()) {
            return@withContext MediaArtwork(backdropUrl = validFallback, posterUrl = validFallback)
        }

        val cacheKey = "movie:$cleanTitle"
        artworkCache[cacheKey]?.let { return@withContext it }

        val movieMeta = com.clotube.app.data.metadata.OnlineMetadataResolver.resolveMovie(cleanTitle)
        if (movieMeta.posterUrl.isNotBlank() && !com.clotube.app.data.metadata.OnlineMetadataResolver.isDriveVideoFrameUrl(movieMeta.posterUrl)) {
            val resolved = MediaArtwork(
                backdropUrl = movieMeta.posterUrl,
                posterUrl = movieMeta.posterUrl
            )
            artworkCache[cacheKey] = resolved
            return@withContext resolved
        }

        try {
            val encodedQuery = Uri.encode(cleanTitle)
            val url = "https://v3-cinemeta.strem.io/catalog/movie/top/search=$encodedQuery.json"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Clotube/1.0 (Android OTT)")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            response.use { res ->
                if (res.isSuccessful) {
                    val body = res.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val metas = json.optJSONArray("metas")

                    if (metas != null && metas.length() > 0) {
                        val firstMatch = metas.getJSONObject(0)
                        val rawPoster = firstMatch.optString("poster", "").takeIf { it.isNotBlank() }
                        val rawBackground = firstMatch.optString("background", "").takeIf { it.isNotBlank() }

                        // High-res upgrades for Amazon/IMDb CDN images
                        val highResPoster = rawPoster?.replace(Regex("""\._V1_.*\.jpg$"""), "._V1_SX800.jpg")
                        val finalBackdrop = rawBackground ?: highResPoster
                        val finalPoster = highResPoster ?: rawBackground

                        if (!finalPoster.isNullOrBlank() && !com.clotube.app.data.metadata.OnlineMetadataResolver.isDriveVideoFrameUrl(finalPoster)) {
                            val resolved = MediaArtwork(
                                backdropUrl = finalBackdrop,
                                posterUrl = finalPoster
                            )
                            artworkCache[cacheKey] = resolved
                            return@withContext resolved
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cinemeta artwork lookup skipped for '$cleanTitle': ${e.message}")
        }

        // Guaranteed fallback: IMDb Suggestion API
        val imdbArtwork = fetchImdbSuggestionArtwork(cleanTitle)
        if (imdbArtwork != null) {
            artworkCache[cacheKey] = imdbArtwork
            return@withContext imdbArtwork
        }

        val fallback = MediaArtwork(backdropUrl = validFallback, posterUrl = validFallback)
        artworkCache[cacheKey] = fallback
        fallback
    }

    /**
     * Resolves official TV series backdrop and poster URLs by series name.
     */
    suspend fun resolveSeriesArtwork(
        rawTitle: String,
        fallbackThumb: String? = null
    ): MediaArtwork = withContext(Dispatchers.IO) {
        val cleanTitle = cleanSearchTitle(rawTitle)
        val validFallback = fallbackThumb?.takeIf { !com.clotube.app.data.metadata.OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
        if (cleanTitle.isBlank()) {
            return@withContext MediaArtwork(backdropUrl = validFallback, posterUrl = validFallback)
        }

        val cacheKey = "series:$cleanTitle"
        artworkCache[cacheKey]?.let { return@withContext it }

        val seriesMeta = com.clotube.app.data.metadata.OnlineMetadataResolver.resolveSeries(cleanTitle)
        if (seriesMeta.posterUrl.isNotBlank() && !com.clotube.app.data.metadata.OnlineMetadataResolver.isDriveVideoFrameUrl(seriesMeta.posterUrl)) {
            val resolved = MediaArtwork(
                backdropUrl = seriesMeta.posterUrl,
                posterUrl = seriesMeta.posterUrl
            )
            artworkCache[cacheKey] = resolved
            return@withContext resolved
        }

        try {
            val encodedQuery = Uri.encode(cleanTitle)
            val url = "https://v3-cinemeta.strem.io/catalog/series/top/search=$encodedQuery.json"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Clotube/1.0 (Android OTT)")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            response.use { res ->
                if (res.isSuccessful) {
                    val body = res.body?.string().orEmpty()
                    val json = JSONObject(body)
                    val metas = json.optJSONArray("metas")

                    if (metas != null && metas.length() > 0) {
                        val firstMatch = metas.getJSONObject(0)
                        val rawPoster = firstMatch.optString("poster", "").takeIf { it.isNotBlank() }
                        val rawBackground = firstMatch.optString("background", "").takeIf { it.isNotBlank() }

                        val highResPoster = rawPoster?.replace(Regex("""\._V1_.*\.jpg$"""), "._V1_SX800.jpg")
                        val finalBackdrop = rawBackground ?: highResPoster
                        val finalPoster = highResPoster ?: rawBackground

                        if (!finalPoster.isNullOrBlank() && !com.clotube.app.data.metadata.OnlineMetadataResolver.isDriveVideoFrameUrl(finalPoster)) {
                            val resolved = MediaArtwork(
                                backdropUrl = finalBackdrop,
                                posterUrl = finalPoster
                            )
                            artworkCache[cacheKey] = resolved
                            return@withContext resolved
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cinemeta series artwork lookup skipped for '$cleanTitle': ${e.message}")
        }

        // Guaranteed fallback: IMDb Suggestion API
        val imdbArtwork = fetchImdbSuggestionArtwork(cleanTitle)
        if (imdbArtwork != null) {
            artworkCache[cacheKey] = imdbArtwork
            return@withContext imdbArtwork
        }

        val fallback = MediaArtwork(backdropUrl = validFallback, posterUrl = validFallback)
        artworkCache[cacheKey] = fallback
        fallback
    }

    private fun fetchImdbSuggestionArtwork(cleanTitle: String): MediaArtwork? {
        val cleanLower = cleanTitle.lowercase().trim()
        if (cleanLower.isBlank()) return null
        val firstChar = cleanLower.firstOrNull { it.isLetterOrDigit() } ?: 'a'
        val encodedQuery = Uri.encode(cleanLower.replace(' ', '_'))
        val imdbUrl = "https://v3.sg.media-imdb.com/suggestion/x/$firstChar/$encodedQuery.json"

        return try {
            val request = Request.Builder()
                .url(imdbUrl)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            response.use { res ->
                if (!res.isSuccessful) return null
                val body = res.body?.string().orEmpty()
                val json = JSONObject(body)
                val suggestions = json.optJSONArray("d")
                if (suggestions != null && suggestions.length() > 0) {
                    for (i in 0 until suggestions.length()) {
                        val item = suggestions.getJSONObject(i)
                        val img = item.optJSONObject("i")
                        val rawImgUrl = img?.optString("imageUrl", "")
                        if (!rawImgUrl.isNullOrBlank()) {
                            val highRes = rawImgUrl.replace(Regex("""\._V1_.*\.jpg$"""), "._V1_SX800.jpg")
                            return MediaArtwork(backdropUrl = highRes, posterUrl = highRes)
                        }
                    }
                }
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "IMDb suggestion lookup failed for '$cleanTitle': ${e.message}")
            null
        }
    }

    /**
     * Extracts pure movie or series title suitable for metadata search engines.
     * Strips container extensions, resolution tags, audio codecs, brackets, and release years.
     * e.g., "Jack Reacher (2012) [1080p].mkv" -> "Jack Reacher"
     */
    fun cleanSearchTitle(raw: String): String {
        return raw
            .replace(Regex("""\.(mkv|mp4|webm|avi|mov|ts|m4v)$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\[.*?\]"""), "") // Remove [1080p], [YTS.LT], etc.
            .replace(Regex("""\((19|20)\d{2}\)"""), "") // Remove (2012)
            .replace(Regex("""\b(19|20)\d{2}\b"""), "") // Remove standalone years
            .replace(Regex("""(?i)[._ -]*(1080p|720p|2160p|4k|60fps|bluray|web-dl|webrip|hevc|x265|x264|aac|dts|remux|h264|hdr|uhd|hindi|english|dual audio).*"""), "")
            .replace(".", " ")
            .replace("_", " ")
            .replace("-", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}
