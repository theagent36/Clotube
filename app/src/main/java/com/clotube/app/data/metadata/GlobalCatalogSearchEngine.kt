package com.clotube.app.data.metadata

import android.net.Uri
import android.util.Log
import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import com.clotube.app.data.DriveCatalogMatcher
import com.clotube.app.data.WatchlistManager
import com.clotube.app.model.Movie
import com.clotube.app.model.TvSeries
import com.clotube.app.model.WatchlistMediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.Serializable
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Unified search result representation spanning local Google Drive media
 * and global online catalogs (TMDB, Cinemeta, IMDb).
 */
@Keep
@Immutable
data class GlobalSearchResult(
    val id: String,                      // Drive ID if in Drive, or "tmdb_movie_<id>" / "tmdb_tv_<id>" / "ext_<slug>"
    val title: String,
    val normalizedTitle: String,
    val mediaType: WatchlistMediaType,   // MOVIE or SERIES
    val year: String = "",
    val genre: String = "",
    val overview: String = "",
    val posterUrl: String = "",
    val backdropUrl: String = "",
    val rating: String = "",
    val isInDrive: Boolean = false,
    val driveMovie: Movie? = null,
    val driveSeries: TvSeries? = null
) : Serializable

/**
 * High-performance hybrid search engine combining the user's private Google Drive
 * catalog with global online entertainment databases (TMDB primary + Cinemeta / IMDb fallback).
 */
object GlobalCatalogSearchEngine {

    private const val TAG = "GlobalSearchEngine"
    private const val TMDB_API_KEY = "84148a68b492c79f33269f8323e0702e"

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .build()

    // Thread-safe memory cache keyed by normalized query
    private val queryCache = ConcurrentHashMap<String, List<GlobalSearchResult>>()

    /**
     * Extracts a 4-digit release year from a title string if present.
     */
    fun extractYear(title: String): String {
        val match = Regex("""\b(19\d\d|20\d\d)\b""").find(title)
        return match?.value.orEmpty()
    }

    /**
     * Retrieves cached online results for a query if already resolved.
     */
    fun getCachedOnlineResults(query: String): List<GlobalSearchResult> {
        val key = query.trim().lowercase()
        return queryCache[key] ?: emptyList()
    }

    /**
     * Clears all in-memory query caches.
     */
    fun clearCache() {
        queryCache.clear()
    }

    /**
     * Executes an online search across global databases with primary and fallback providers.
     */
    suspend fun searchOnlineCatalog(query: String): List<GlobalSearchResult> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.length < 2) return@withContext emptyList()

        val cacheKey = cleanQuery.lowercase()
        queryCache[cacheKey]?.let { return@withContext it }

        // 1. Primary Engine: TMDB v3 Multi-Search
        val tmdbResults = searchTmdb(cleanQuery)
        if (tmdbResults.isNotEmpty()) {
            queryCache[cacheKey] = tmdbResults
            return@withContext tmdbResults
        }

        // 2. Fallback Engine: Cinemeta + IMDb Suggestions
        Log.d(TAG, "TMDB returned no results or timed out for '$cleanQuery', executing fallback engines...")
        val fallbackResults = searchFallbackEngines(cleanQuery)
        queryCache[cacheKey] = fallbackResults
        return@withContext fallbackResults
    }

    /**
     * Primary search implementation using TMDB v3 Multi-Search API.
     */
    private fun searchTmdb(query: String): List<GlobalSearchResult> {
        val results = mutableListOf<GlobalSearchResult>()
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://api.themoviedb.org/3/search/multi?api_key=$TMDB_API_KEY&query=$encodedQuery&include_adult=false&language=en-US&page=1"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) Clotube/3.7")
                .header("Accept", "application/json")
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "TMDB HTTP error ${response.code} for query '$query'")
                return emptyList()
            }

            val body = response.body?.string().orEmpty()
            if (body.isBlank()) return emptyList()

            val json = JSONObject(body)
            val jsonResults = json.optJSONArray("results") ?: return emptyList()

            for (i in 0 until jsonResults.length()) {
                val item = jsonResults.optJSONObject(i) ?: continue
                val mediaTypeRaw = item.optString("media_type")

                val isMovie = mediaTypeRaw == "movie"
                val isTv = mediaTypeRaw == "tv"
                if (!isMovie && !isTv) continue

                val id = if (isMovie) "tmdb_movie_${item.optLong("id")}" else "tmdb_tv_${item.optLong("id")}"
                val title = if (isMovie) {
                    item.optString("title").ifBlank { item.optString("original_title") }
                } else {
                    item.optString("name").ifBlank { item.optString("original_name") }
                }.trim()

                if (title.isBlank()) continue

                val releaseDate = if (isMovie) item.optString("release_date") else item.optString("first_air_date")
                val year = releaseDate.take(4).filter { it.isDigit() }

                val posterPath = item.optString("poster_path").trim()
                val posterUrl = if (posterPath.isNotBlank() && posterPath != "null") {
                    "https://image.tmdb.org/t/p/w500$posterPath"
                } else ""

                val backdropPath = item.optString("backdrop_path").trim()
                val backdropUrl = if (backdropPath.isNotBlank() && backdropPath != "null") {
                    "https://image.tmdb.org/t/p/w780$backdropPath"
                } else ""

                val overview = item.optString("overview", "").trim()
                val voteAverage = item.optDouble("vote_average", 0.0)
                val rating = if (voteAverage > 0.0) String.format(Locale.US, "%.1f", voteAverage) else ""

                val mediaType = if (isMovie) WatchlistMediaType.MOVIE else WatchlistMediaType.SERIES
                val genre = if (isMovie) "Movie" else "TV Series"

                results.add(
                    GlobalSearchResult(
                        id = id,
                        title = title,
                        normalizedTitle = WatchlistManager.normalizeTitle(title),
                        mediaType = mediaType,
                        year = year,
                        genre = genre,
                        overview = overview,
                        posterUrl = posterUrl,
                        backdropUrl = backdropUrl,
                        rating = rating,
                        isInDrive = false,
                        driveMovie = null,
                        driveSeries = null
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "TMDB search failed for '$query': ${e.message}")
        }
        return results
    }

    /**
     * Fallback multi-source search combining Cinemeta and IMDb Suggestions.
     */
    private fun searchFallbackEngines(query: String): List<GlobalSearchResult> {
        val mergedFallback = mutableListOf<GlobalSearchResult>()
        val seenNorms = mutableSetOf<String>()

        // 1. Cinemeta Movies
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val movieUrl = "https://v3-cinemeta.strem.io/catalog/movie/top/search=$encodedQuery.json"
            val request = Request.Builder().url(movieUrl).header("User-Agent", "Clotube/3.7").build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                if (body.isNotBlank()) {
                    val json = JSONObject(body)
                    val metas = json.optJSONArray("metas")
                    if (metas != null) {
                        for (i in 0 until metas.length()) {
                            val meta = metas.optJSONObject(i) ?: continue
                            val name = meta.optString("name").trim()
                            if (name.isBlank()) continue
                            val norm = WatchlistManager.normalizeTitle(name)
                            if (seenNorms.add(norm + "_movie")) {
                                val year = meta.optString("year").take(4).ifBlank { meta.optString("releaseInfo").take(4) }
                                val poster = meta.optString("poster", "").trim()
                                val overview = meta.optString("description", "").trim()
                                val rating = meta.optString("imdbRating", "").trim()
                                val extId = meta.optString("id", "ext_${norm}").trim()

                                mergedFallback.add(
                                    GlobalSearchResult(
                                        id = extId,
                                        title = name,
                                        normalizedTitle = norm,
                                        mediaType = WatchlistMediaType.MOVIE,
                                        year = year,
                                        genre = "Movie",
                                        overview = overview,
                                        posterUrl = poster,
                                        backdropUrl = "",
                                        rating = rating,
                                        isInDrive = false
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cinemeta movie search failed: ${e.message}")
        }

        // 2. Cinemeta TV Series
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val seriesUrl = "https://v3-cinemeta.strem.io/catalog/series/top/search=$encodedQuery.json"
            val request = Request.Builder().url(seriesUrl).header("User-Agent", "Clotube/3.7").build()
            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                if (body.isNotBlank()) {
                    val json = JSONObject(body)
                    val metas = json.optJSONArray("metas")
                    if (metas != null) {
                        for (i in 0 until metas.length()) {
                            val meta = metas.optJSONObject(i) ?: continue
                            val name = meta.optString("name").trim()
                            if (name.isBlank()) continue
                            val norm = WatchlistManager.normalizeTitle(name)
                            if (seenNorms.add(norm + "_series")) {
                                val year = meta.optString("year").take(4).ifBlank { meta.optString("releaseInfo").take(4) }
                                val poster = meta.optString("poster", "").trim()
                                val overview = meta.optString("description", "").trim()
                                val rating = meta.optString("imdbRating", "").trim()
                                val extId = meta.optString("id", "ext_${norm}").trim()

                                mergedFallback.add(
                                    GlobalSearchResult(
                                        id = extId,
                                        title = name,
                                        normalizedTitle = norm,
                                        mediaType = WatchlistMediaType.SERIES,
                                        year = year,
                                        genre = "TV Series",
                                        overview = overview,
                                        posterUrl = poster,
                                        backdropUrl = "",
                                        rating = rating,
                                        isInDrive = false
                                    )
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cinemeta series search failed: ${e.message}")
        }

        // 3. IMDb Suggestion API
        try {
            val slug = query.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
            if (slug.isNotBlank()) {
                val imdbUrl = "https://v3.sg.media-imdb.com/suggestion/x/$slug.json"
                val request = Request.Builder()
                    .url(imdbUrl)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    .build()
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    if (body.isNotBlank()) {
                        val json = JSONObject(body)
                        val items = json.optJSONArray("d")
                        if (items != null) {
                            for (i in 0 until items.length()) {
                                val item = items.optJSONObject(i) ?: continue
                                val title = item.optString("l").trim()
                                if (title.isBlank()) continue

                                val q = item.optString("q", "").lowercase()
                                val qid = item.optString("qid", "").lowercase()
                                val isSeries = q.contains("series") || q.contains("tv") || qid.contains("series")
                                val mediaType = if (isSeries) WatchlistMediaType.SERIES else WatchlistMediaType.MOVIE
                                val norm = WatchlistManager.normalizeTitle(title)

                                if (seenNorms.add(norm + "_" + mediaType.name.lowercase())) {
                                    val year = item.optInt("y", 0).let { if (it > 0) it.toString() else "" }
                                    var posterUrl = item.optJSONObject("i")?.optString("imageUrl", "").orEmpty()
                                    if (posterUrl.contains("._V1_")) {
                                        posterUrl = posterUrl.replace(Regex("\\._V1_.*(\\.[a-zA-Z]+)$"), "._V1_SX600$1")
                                    }
                                    val id = "imdb_${item.optString("id", norm)}"

                                    mergedFallback.add(
                                        GlobalSearchResult(
                                            id = id,
                                            title = title,
                                            normalizedTitle = norm,
                                            mediaType = mediaType,
                                            year = year,
                                            genre = if (isSeries) "TV Series" else "Movie",
                                            posterUrl = posterUrl,
                                            isInDrive = false
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "IMDb suggestion search failed: ${e.message}")
        }

        return mergedFallback
    }

    /**
     * Merges user's local Google Drive catalog with external online search results,
     * ensuring titles present in Drive are marked as playable, enriched, and non-duplicated.
     */
    fun mergeDriveAndOnlineResults(
        query: String,
        driveMovies: List<Movie>,
        driveSeries: List<TvSeries>,
        onlineResults: List<GlobalSearchResult>
    ): List<GlobalSearchResult> {
        val cleanQuery = query.trim()

        val driveMatches = mutableListOf<GlobalSearchResult>()
        val matchedDriveMovieIds = mutableSetOf<String>()
        val matchedDriveSeriesIds = mutableSetOf<String>()
        val seenDriveMovieNorms = mutableSetOf<String>()
        val seenDriveSeriesNorms = mutableSetOf<String>()

        // 1. Filter Drive Movies directly matching query
        for (m in driveMovies) {
            if (cleanQuery.isEmpty() || DriveCatalogMatcher.matchesSearchQuery(m, cleanQuery)) {
                val normTitle = WatchlistManager.normalizeTitle(m.title)
                val normKey = normTitle.ifBlank { m.id }
                if (matchedDriveMovieIds.add(m.id) && (normTitle.isBlank() || seenDriveMovieNorms.add(normKey))) {
                    driveMatches.add(
                        GlobalSearchResult(
                            id = m.id,
                            title = m.title,
                            normalizedTitle = normTitle,
                            mediaType = WatchlistMediaType.MOVIE,
                            year = extractYear(m.title),
                            genre = m.genre,
                            overview = "",
                            posterUrl = m.thumbnailUrl.orEmpty(),
                            backdropUrl = "",
                            rating = "",
                            isInDrive = true,
                            driveMovie = m,
                            driveSeries = null
                        )
                    )
                }
            }
        }

        // 2. Filter Drive TV Series directly matching query
        for (s in driveSeries) {
            if (cleanQuery.isEmpty() || DriveCatalogMatcher.matchesSearchQuery(s, cleanQuery)) {
                val normTitle = WatchlistManager.normalizeTitle(s.title)
                val normKey = normTitle.ifBlank { s.id }
                if (matchedDriveSeriesIds.add(s.id) && (normTitle.isBlank() || seenDriveSeriesNorms.add(normKey))) {
                    val thumb = s.posterUrl?.ifBlank { null }
                        ?: s.backdropUrl?.ifBlank { null }
                        ?: s.thumbnailUrl
                    driveMatches.add(
                        GlobalSearchResult(
                            id = s.id,
                            title = s.title,
                            normalizedTitle = normTitle,
                            mediaType = WatchlistMediaType.SERIES,
                            year = extractYear(s.title),
                            genre = s.genre,
                            overview = s.synopsis,
                            posterUrl = thumb.orEmpty(),
                            backdropUrl = s.backdropUrl.orEmpty(),
                            rating = s.ratingBadge,
                            isInDrive = true,
                            driveMovie = null,
                            driveSeries = s
                        )
                    )
                }
            }
        }

        // 3. Deduplicate and merge online results against Drive catalog
        val mergedOnline = mutableListOf<GlobalSearchResult>()
        val seenOnlineNorms = HashSet<String>()

        for (online in onlineResults) {
            val normOnline = WatchlistManager.normalizeTitle(online.title)
            if (normOnline.isBlank()) continue

            // Check if this online result corresponds to a Movie or TV Series in Drive
            val matchingMovie = DriveCatalogMatcher.findMatchingDriveMovie(driveMovies, online.id, online.title)
            val matchingSeries = if (matchingMovie == null) {
                DriveCatalogMatcher.findMatchingDriveSeries(driveSeries, online.id, online.title)
            } else null

            if (matchingMovie != null) {
                // The item IS in Google Drive
                val existingDriveIdx = driveMatches.indexOfFirst { it.id == matchingMovie.id }
                if (existingDriveIdx != -1) {
                    // Enrich existing Drive search result with online poster, backdrop, year, overview
                    val current = driveMatches[existingDriveIdx]
                    driveMatches[existingDriveIdx] = current.copy(
                        posterUrl = if (current.posterUrl.isBlank()) online.posterUrl else current.posterUrl,
                        backdropUrl = if (current.backdropUrl.isBlank()) online.backdropUrl else current.backdropUrl,
                        year = if (current.year.isBlank()) online.year else current.year,
                        overview = if (current.overview.isBlank()) online.overview else current.overview,
                        rating = if (current.rating.isBlank()) online.rating else current.rating
                    )
                } else if (matchedDriveMovieIds.add(matchingMovie.id)) {
                    // Add as a playable Drive movie item enriched with online metadata
                    val thumb = matchingMovie.thumbnailUrl?.ifBlank { null } ?: online.posterUrl
                    driveMatches.add(
                        GlobalSearchResult(
                            id = matchingMovie.id,
                            title = matchingMovie.title,
                            normalizedTitle = WatchlistManager.normalizeTitle(matchingMovie.title),
                            mediaType = WatchlistMediaType.MOVIE,
                            year = online.year.ifBlank { extractYear(matchingMovie.title) },
                            genre = matchingMovie.genre,
                            overview = online.overview,
                            posterUrl = thumb.orEmpty(),
                            backdropUrl = online.backdropUrl,
                            rating = online.rating,
                            isInDrive = true,
                            driveMovie = matchingMovie,
                            driveSeries = null
                        )
                    )
                }
                continue
            }

            if (matchingSeries != null) {
                // The series IS in Google Drive
                val existingDriveIdx = driveMatches.indexOfFirst { it.id == matchingSeries.id }
                if (existingDriveIdx != -1) {
                    val current = driveMatches[existingDriveIdx]
                    driveMatches[existingDriveIdx] = current.copy(
                        posterUrl = if (current.posterUrl.isBlank()) online.posterUrl else current.posterUrl,
                        backdropUrl = if (current.backdropUrl.isBlank()) online.backdropUrl else current.backdropUrl,
                        year = if (current.year.isBlank()) online.year else current.year,
                        overview = if (current.overview.isBlank()) online.overview else current.overview,
                        rating = if (current.rating.isBlank()) online.rating else current.rating
                    )
                } else if (matchedDriveSeriesIds.add(matchingSeries.id)) {
                    val thumb = matchingSeries.posterUrl?.ifBlank { null }
                        ?: matchingSeries.backdropUrl?.ifBlank { null }
                        ?: online.posterUrl.ifBlank { null }
                        ?: matchingSeries.thumbnailUrl
                    driveMatches.add(
                        GlobalSearchResult(
                            id = matchingSeries.id,
                            title = matchingSeries.title,
                            normalizedTitle = WatchlistManager.normalizeTitle(matchingSeries.title),
                            mediaType = WatchlistMediaType.SERIES,
                            year = online.year.ifBlank { extractYear(matchingSeries.title) },
                            genre = matchingSeries.genre,
                            overview = online.overview.ifBlank { matchingSeries.synopsis },
                            posterUrl = thumb.orEmpty(),
                            backdropUrl = online.backdropUrl.ifBlank { matchingSeries.backdropUrl.orEmpty() },
                            rating = online.rating.ifBlank { matchingSeries.ratingBadge },
                            isInDrive = true,
                            driveMovie = null,
                            driveSeries = matchingSeries
                        )
                    )
                }
                continue
            }

            // Truly not present in Google Drive: add as external title
            val key = normOnline + "_" + online.mediaType.name
            if (seenOnlineNorms.add(key)) {
                mergedOnline.add(online.copy(isInDrive = false))
            }
        }

        // Combine: Drive matches first, then external online matches with canonical deduplication
        val combined = ArrayList<GlobalSearchResult>(driveMatches.size + mergedOnline.size)
        combined.addAll(driveMatches)
        combined.addAll(mergedOnline)

        val dedupedMap = LinkedHashMap<String, GlobalSearchResult>()
        for (item in combined) {
            val key = when {
                item.driveMovie != null -> "drive_movie_${item.driveMovie.id}"
                item.driveSeries != null -> "drive_series_${item.driveSeries.id}"
                else -> "norm_${item.mediaType.name}_${WatchlistManager.normalizeTitle(item.title).ifBlank { item.id }}"
            }
            val existing = dedupedMap[key]
            if (existing == null) {
                dedupedMap[key] = item
            } else {
                val winner = if (!existing.isInDrive && item.isInDrive) item
                else if (existing.posterUrl.isBlank() && item.posterUrl.isNotBlank()) item
                else existing
                dedupedMap[key] = winner
            }
        }
        return dedupedMap.values.toList()
    }
}
