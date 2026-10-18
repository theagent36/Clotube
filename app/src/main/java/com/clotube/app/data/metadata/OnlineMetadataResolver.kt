package com.clotube.app.data.metadata

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

data class MovieMetadata(
    val officialTitle: String,
    val posterUrl: String = "",
    val releaseYear: String = "",
    val genre: String = "Movie • Cinema"
)

data class SeriesMetadata(
    val tvMazeId: Long,
    val officialTitle: String,
    val posterUrl: String = "",
    val genres: String = "TV Series • Drama"
)

data class EpisodeMetadata(
    val officialTitle: String,
    val thumbnailUrl: String = "",
    val durationMinutes: Long = 0L
)

object OnlineMetadataResolver {

    private const val TAG = "OnlineMetadataResolver"

    private fun logD(tag: String, msg: String) {
        try {
            Log.d(tag, msg)
        } catch (_: Throwable) {
            println("[$tag] $msg")
        }
    }

    private fun logE(tag: String, msg: String, tr: Throwable? = null) {
        try {
            if (tr != null) Log.e(tag, msg, tr) else Log.e(tag, msg)
        } catch (_: Throwable) {
            System.err.println("[$tag] $msg ${tr?.message.orEmpty()}")
        }
    }

    private val movieCache = ConcurrentHashMap<String, MovieMetadata>()
    private val seriesCache = ConcurrentHashMap<String, SeriesMetadata>()
    private val episodeCache = ConcurrentHashMap<String, EpisodeMetadata>()
    private val showEpisodesCache = ConcurrentHashMap<Long, Map<Pair<Int, Int>, EpisodeMetadata>>()

    fun clearCache() {
        movieCache.clear()
        seriesCache.clear()
        episodeCache.clear()
        showEpisodesCache.clear()
    }

    // ========================================================
    // 1. MOVIE METADATA RESOLUTION
    // ========================================================

    suspend fun resolveMovie(rawFileName: String): MovieMetadata = withContext(Dispatchers.IO) {
        val trimmed = rawFileName.trim()
        movieCache[trimmed]?.let { return@withContext it }

        val (cleanQuery, releaseYear) = extractMovieQueryAndYear(trimmed)
        if (cleanQuery.isBlank()) {
            val fallback = MovieMetadata(officialTitle = toTitleCase(trimmed.substringBeforeLast('.')))
            movieCache[trimmed] = fallback
            return@withContext fallback
        }

        // Engine 1: IMDb Suggestion API (Universal: Hollywood, Bollywood, Amazon, Netflix)
        val imdbResult = fetchFromImdb(cleanQuery, releaseYear)
        if (imdbResult != null && imdbResult.posterUrl.isNotBlank()) {
            movieCache[trimmed] = imdbResult
            return@withContext imdbResult
        }

        // Engine 2: iTunes Store API (Secondary)
        val itunesResult = fetchFromItunes(cleanQuery, releaseYear)
        if (itunesResult != null) {
            val mergedResult = if (imdbResult != null && itunesResult.posterUrl.isBlank()) {
                imdbResult.copy(posterUrl = itunesResult.posterUrl)
            } else {
                itunesResult
            }
            movieCache[trimmed] = mergedResult
            return@withContext mergedResult
        }

        // Engine 3: Clean Fallback
        val safeFallback = imdbResult ?: MovieMetadata(
            officialTitle = toTitleCase(cleanQuery),
            posterUrl = "",
            releaseYear = releaseYear ?: "",
            genre = "Movie • Cinema"
        )
        movieCache[trimmed] = safeFallback
        safeFallback
    }

    // ========================================================
    // ENGINE 1: IMDB SUGGESTION API (Free, Keyless, Universal)
    // ========================================================

    private fun fetchFromImdb(query: String, targetYear: String?): MovieMetadata? {
        var connection: HttpURLConnection? = null
        try {
            // Slugify query: "The Vast of Night" -> "the_vast_of_night"
            val slug = query.lowercase()
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')

            if (slug.isBlank()) return null

            val endpoint = "https://v3.sg.media-imdb.com/suggestion/x/$slug.json"
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(responseText)
                val results = json.optJSONArray("d") ?: return null

                var bestObj: JSONObject? = null

                for (i in 0 until results.length()) {
                    val item = results.getJSONObject(i)
                    val title = item.optString("l", "")
                    val year = item.optInt("y", 0).toString()
                    val type = item.optString("q", "")
                    val qid = item.optString("qid", "")

                    if (isTitleSimilar(query, title)) {
                        val isMovie = type == "feature" || qid == "movie" || type == "TV movie" || type == "video" || item.optString("id", "").startsWith("tt")
                        val hasPoster = item.optJSONObject("i") != null
                        val isFeature = type == "feature" || qid == "movie"

                        // Prefer exact year match for feature films with poster
                        if (!targetYear.isNullOrBlank() && year == targetYear && isFeature && hasPoster) {
                            bestObj = item
                            break
                        }
                        if (bestObj == null) {
                            bestObj = item
                        } else {
                            val bestIsFeature = bestObj.optString("q") == "feature" || bestObj.optString("qid") == "movie"
                            if (!bestIsFeature && isFeature) {
                                bestObj = item
                            } else if (!bestObj.has("i") && hasPoster) {
                                bestObj = item
                            }
                        }
                    }
                }

                if (bestObj != null) {
                    val officialTitle = bestObj.optString("l", query).trim()
                    val year = bestObj.optInt("y", 0).let { if (it > 0) it.toString() else (targetYear ?: "") }
                    val imageObj = bestObj.optJSONObject("i")
                    var posterUrl = imageObj?.optString("imageUrl", "") ?: ""

                    // Upgrade IMDb poster to high resolution by stripping thumbnail scaling specifiers
                    if (posterUrl.contains("._V1_")) {
                        posterUrl = posterUrl.replace(Regex("\\._V1_.*(\\.[a-zA-Z]+)$"), "._V1_$1")
                    }

                    logD(TAG, "IMDb match for '$query' -> '$officialTitle' ($year)")
                    return MovieMetadata(
                        officialTitle = officialTitle,
                        posterUrl = posterUrl,
                        releaseYear = year,
                        genre = "Movie • Cinema"
                    )
                }
            }
        } catch (e: Exception) {
            logE(TAG, "IMDb lookup failed for '$query': ${e.message}")
        } finally {
            connection?.disconnect()
        }
        return null
    }

    // ========================================================
    // ENGINE 2: ITUNES SEARCH API
    // ========================================================

    private fun fetchFromItunes(query: String, targetYear: String?): MovieMetadata? {
        var connection: HttpURLConnection? = null
        try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val endpoint = "https://itunes.apple.com/search?term=$encoded&entity=movie&limit=8"
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                setRequestProperty("User-Agent", "Clotube/1.0 (Android)")
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)
                val count = json.optInt("resultCount", 0)

                if (count > 0) {
                    val results = json.getJSONArray("results")
                    var bestMatch: JSONObject? = null

                    for (i in 0 until results.length()) {
                        val item = results.getJSONObject(i)
                        val title = item.optString("trackName", "")
                        val releaseDate = item.optString("releaseDate", "")

                        if (isTitleSimilar(query, title)) {
                            if (!targetYear.isNullOrBlank() && releaseDate.startsWith(targetYear)) {
                                bestMatch = item
                                break
                            }
                            if (bestMatch == null) {
                                bestMatch = item
                            }
                        }
                    }

                    if (bestMatch != null) {
                        val officialTitle = bestMatch.optString("trackName", query).trim()
                        val rawThumb = bestMatch.optString("artworkUrl100", "")
                        val highResPoster = rawThumb.replace("100x100bb.jpg", "600x600bb.jpg")
                        val genre = bestMatch.optString("primaryGenreName", "Cinema")
                        val year = bestMatch.optString("releaseDate", "").take(4)

                        logD(TAG, "iTunes match for '$query' -> '$officialTitle' ($year)")
                        return MovieMetadata(
                            officialTitle = officialTitle,
                            posterUrl = highResPoster,
                            releaseYear = year,
                            genre = "Movie • $genre"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            logE(TAG, "iTunes lookup failed for '$query': ${e.message}")
        } finally {
            connection?.disconnect()
        }
        return null
    }

    // ========================================================
    // 2. SERIES & EPISODE RESOLUTION (TVMaze API)
    // ========================================================

    suspend fun resolveSeries(rawSeriesName: String): SeriesMetadata = withContext(Dispatchers.IO) {
        val trimmed = rawSeriesName.trim()
        seriesCache[trimmed]?.let { return@withContext it }

        val cleanTitle = cleanSeriesTitle(trimmed)
        var connection: HttpURLConnection? = null

        try {
            val encoded = URLEncoder.encode(cleanTitle, "UTF-8")
            val endpoint = "https://api.tvmaze.com/singlesearch/shows?q=$encoded"
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", "Clotube/1.0")
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(response)

                val tvMazeId = json.optLong("id", -1L)
                val officialName = json.optString("name", cleanTitle)
                val imageObj = json.optJSONObject("image")
                val posterUrl = imageObj?.optString("original") ?: imageObj?.optString("medium") ?: ""

                val genresArray = json.optJSONArray("genres")
                val genresList = mutableListOf<String>()
                if (genresArray != null) {
                    for (i in 0 until genresArray.length()) {
                        genresList.add(genresArray.getString(i))
                    }
                }
                val genreString = if (genresList.isNotEmpty()) genresList.joinToString(" • ") else "Drama • Mystery"

                val result = SeriesMetadata(
                    tvMazeId = tvMazeId,
                    officialTitle = officialName,
                    posterUrl = posterUrl,
                    genres = genreString
                )
                seriesCache[trimmed] = result
                return@withContext result
            }
        } catch (e: Exception) {
            logE(TAG, "Series lookup failed for '$cleanTitle': ${e.message}")
        } finally {
            connection?.disconnect()
        }

        val fallback = SeriesMetadata(tvMazeId = -1L, officialTitle = cleanTitle, posterUrl = "", genres = "TV Series")
        seriesCache[trimmed] = fallback
        fallback
    }

    suspend fun resolveEpisode(
        seriesName: String,
        tvMazeSeriesId: Long,
        seasonNumber: Int,
        episodeNumber: Int,
        rawFileName: String
    ): EpisodeMetadata = withContext(Dispatchers.IO) {
        val targetShowId = if (tvMazeSeriesId > 0L) tvMazeSeriesId else resolveSeries(seriesName).tvMazeId

        if (targetShowId > 0L) {
            // 1. Fetch all episodes for this show in one batch if not yet cached
            var episodesMap = showEpisodesCache[targetShowId]
            if (episodesMap == null) {
                episodesMap = fetchAllEpisodesForShow(targetShowId)
                showEpisodesCache[targetShowId] = episodesMap
            }

            // 2. Instant O(1) in-memory lookup
            episodesMap[Pair(seasonNumber, episodeNumber)]?.let { return@withContext it }
        }

        val fallbackTitle = cleanEpisodeTitleFromFileName(rawFileName, seasonNumber, episodeNumber, seriesName)
        EpisodeMetadata(officialTitle = fallbackTitle, thumbnailUrl = "", durationMinutes = 0L)
    }

    private fun fetchAllEpisodesForShow(showId: Long): Map<Pair<Int, Int>, EpisodeMetadata> {
        val map = mutableMapOf<Pair<Int, Int>, EpisodeMetadata>()
        var connection: HttpURLConnection? = null
        try {
            val endpoint = "https://api.tvmaze.com/shows/$showId/episodes"
            connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", "Clotube/1.0 (Android)")
            }

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                val response = connection.inputStream.bufferedReader().use { it.readText() }
                val array = JSONArray(response)

                for (i in 0 until array.length()) {
                    val item = array.getJSONObject(i)
                    val s = item.optInt("season", -1)
                    val e = item.optInt("number", -1)
                    val name = item.optString("name", "").trim()
                    val runtime = item.optLong("runtime", 0L)
                    val imageObj = item.optJSONObject("image")
                    val thumb = imageObj?.optString("original") ?: imageObj?.optString("medium") ?: ""

                    if (s > 0 && e > 0 && name.isNotBlank()) {
                        map[Pair(s, e)] = EpisodeMetadata(
                            officialTitle = name,
                            thumbnailUrl = thumb,
                            durationMinutes = runtime
                        )
                    }
                }
            }
        } catch (e: Exception) {
            logE(TAG, "Batch episodes fetch failed for show $showId: ${e.message}")
        } finally {
            connection?.disconnect()
        }
        return map
    }

    // ========================================================
    // ADVANCED REGEX STRIPPER & SIMILARITY CHECKER
    // ========================================================

    fun extractMovieQueryAndYear(raw: String): Pair<String, String?> {
        var text = raw.substringBeforeLast('.')

        // 1. Strip domain names (e.g. "www.uindex.org", "uindex.net", "Www Uindex Org")
        text = text.replace(Regex("(?i)\\b(https?://)?(www\\.)?[a-z0-9_-]+[.\\s]+(org|com|net|in|top|xyz|me|io|co|info|biz|club|site|online|tv|ru|cc|to)\\b"), " ")

        // 2. Strip uploader & tracker watermarks & standalone domain indicators
        text = text.replace(Regex("(?i)\\b(www\\s*)?(uindex|uindexorg|yts|yify|rarbg|psa|pahe|galaxytv|tgx|qxr|1337x|torrent|extratorrent|eztv|directdl)(\\s*(org|com|net))?\\b"), " ")
        text = text.replace(Regex("(?i)\\b(www|org|com|net)\\b"), " ")

        // Normalize underscores and dots to spaces early so word boundaries \b work on all tags
        text = text.replace('_', ' ').replace('.', ' ')

        // 3. Extract 4-digit release year (1900-2099)
        val yearMatcher = Pattern.compile("\\b(19\\d{2}|20\\d{2})\\b").matcher(text)
        var year: String? = null
        if (yearMatcher.find()) {
            year = yearMatcher.group(1)
        }

        // 4. Strip technical rip tags, codecs, audio, resolutions, and release tags
        val cleanupTags = listOf(
            "(?i)\\b(2160p|1080p|1080i|720p|480p|FHD|UHD|4K|HDTV|WEB-?DL|WEBRip|BluRay|BRRip|DVDRip|IMAX|Remux|WEB(\\s*Line)?|TeleSync|TS|CAM|HDCAM|HDTS|Line)\\b",
            "(?i)\\b(x264|x265|H\\.?264|H\\.?265|HEVC|AVC|10bit|8bit|HDR10?\\+?|DV|Dolby)\\b",
            "(?i)\\b(AAC(5[.\\s]?1|2[.\\s]?0)?|AC3|EAC3|DTS|DDP5[.\\s]?1|DD5[.\\s]?1|5[.\\s]?1CH|2[.\\s]?0CH|MP3|Dual\\s*Audio|Hindi|D)\\b",
            "(?i)\\b(AMZN|NF|ATVP|DSNP|HMAX|MAX)\\b",
            "(?i)\\b(BONE|GalaxyRG|GalaxyTV|YTS|YIFY|RARBG|PSA|Pahe|WORLD)\\b",
            "(?i)\\b\\d+(MB|GB)\\b",
            "(?i)-[A-Za-z0-9_]+$",
            "(?i)\\[[^\\]]*\\]",
            "(?i)\\([^\\)]*\\)"
        )
        for (tag in cleanupTags) {
            text = text.replace(Regex(tag), " ")
        }
        if (year != null) {
            text = text.replace(year, " ")
        }

        // 5. Clean remaining punctuation, dangling parentheses, hyphens, and multi-spaces
        text = text.replace(Regex("[()\\[\\]{}]"), " ")
        text = text.replace(Regex("^\\s*-\\s*|\\s*-\\s*$"), " ")
        text = text.replace(Regex("\\s+"), " ").trim()

        return Pair(text, year)
    }

    fun isTitleSimilar(query: String, candidate: String): Boolean {
        val stopWords = setOf("the", "a", "an", "of", "and", "in", "on", "at", "to", "for", "with", "by", "film", "movie")
        val qTokens = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() && it !in stopWords }
        val cTokens = candidate.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() && it !in stopWords }

        if (qTokens.isEmpty() || cTokens.isEmpty()) return false

        var qMatches = 0
        for (q in qTokens) {
            if (cTokens.any { c -> c == q || (c.length > 3 && (c.contains(q) || q.contains(c))) }) {
                qMatches++
            }
        }

        var cMatches = 0
        for (c in cTokens) {
            if (qTokens.any { q -> q == c || (q.length > 3 && (q.contains(c) || c.contains(q))) }) {
                cMatches++
            }
        }

        val qOverlap = qMatches.toDouble() / qTokens.size.toDouble()
        val cOverlap = cMatches.toDouble() / cTokens.size.toDouble()
        return qOverlap >= 0.40 || cOverlap >= 0.75
    }

    fun cleanSeriesTitle(raw: String): String {
        var text = raw
        val cleanupTags = listOf(
            "(?i)\\b(Complete|Series|Season|S\\d+|Pack|1080p|720p|480p|BluRay|WEB-?DL|WEBRip|x264|x265|HEVC)\\b",
            "(?i)\\[[^\\]]*\\]",
            "(?i)\\([^\\)]*\\)"
        )
        for (tag in cleanupTags) {
            text = text.replace(Regex(tag), "")
        }
        return text.replace('.', ' ').replace('_', ' ').replace('-', ' ').replace(Regex("\\s+"), " ").trim()
    }

    fun cleanEpisodeTitleFromFileName(
        rawFileName: String,
        seasonNumber: Int,
        episodeNumber: Int,
        seriesName: String = ""
    ): String {
        var text = rawFileName.substringBeforeLast('.')

        // 1. Remove dots and underscores first so words and series titles can match cleanly
        text = text.replace('.', ' ').replace('_', ' ')

        // 2. Strip series name if provided
        if (seriesName.isNotBlank()) {
            val normalizedSeries = seriesName.replace('.', ' ').replace('_', ' ').trim()
            val escapedSeries = Regex.escape(normalizedSeries)
            text = text.replace(Regex(escapedSeries, RegexOption.IGNORE_CASE), " ")
        }

        // 3. Strip season and episode codes
        val sTag = "S" + seasonNumber.toString().padStart(2, '0')
        val eTag = "E" + episodeNumber.toString().padStart(2, '0')
        val epCodePattern = "(?i)($sTag$eTag|${seasonNumber}x${episodeNumber}|Episode\\s*0*$episodeNumber)"
        text = text.replace(Regex(epCodePattern), " ")
        text = text.replace(Regex("(?i)\\bS\\d{1,2}[\\s._-]*E\\d{1,3}\\b"), " ")
        text = text.replace(Regex("(?i)\\b\\d{1,2}x\\d{1,3}\\b"), " ")
        text = text.replace(Regex("(?i)\\b(Episode|Ep|E)[\\s._-]*\\d{1,3}\\b"), " ")

        val cleanupTags = listOf(
            "(?i)\\b(2160p|1080p|1080i|720p|480p|FHD|UHD|4K|HDTV|WEB-?DL|WEBRip|BluRay|BRRip|DVDRip)\\b",
            "(?i)\\b(x264|x265|H\\.?264|H\\.?265|HEVC|AVC|10bit|8bit|HDR10?)\\b",
            "(?i)\\b(AAC|AC3|EAC3|DTS|DDP5\\.1|5\\.1CH|2\\.0CH|MP3)\\b",
            "(?i)\\[[^\\]]*\\]",
            "(?i)\\([^\\)]*\\)"
        )
        for (tag in cleanupTags) {
            text = text.replace(Regex(tag), " ")
        }

        // Clean leading/trailing/standalone hyphens and punctuation
        text = text.replace(Regex("^[\\s\\-:—–]+"), "")
        text = text.replace(Regex("[\\s\\-:—–]+$"), "")
        text = text.replace(Regex("\\s+-\\s+"), " ")
        text = text.replace(Regex("\\s+"), " ").trim()

        return if (text.isBlank() || text.equals("Episode", ignoreCase = true) || (seriesName.isNotBlank() && text.equals(seriesName, ignoreCase = true))) {
            "Episode $episodeNumber"
        } else {
            text
        }
    }

    fun parseSeasonAndEpisodeNumbers(fileName: String): Pair<Int, Int>? {
        val sEpPattern = Pattern.compile("(?i)[sS](\\d{1,2})[eE](\\d{1,2})")
        val matcher = sEpPattern.matcher(fileName)
        if (matcher.find()) {
            return Pair(matcher.group(1)?.toIntOrNull() ?: 1, matcher.group(2)?.toIntOrNull() ?: 1)
        }

        val altPattern = Pattern.compile("(?i)(\\d{1,2})x(\\d{1,2})")
        val altMatcher = altPattern.matcher(fileName)
        if (altMatcher.find()) {
            return Pair(altMatcher.group(1)?.toIntOrNull() ?: 1, altMatcher.group(2)?.toIntOrNull() ?: 1)
        }

        val standalonePattern = Pattern.compile("(?i)Episode\\s*(\\d{1,3})")
        val standaloneMatcher = standalonePattern.matcher(fileName)
        if (standaloneMatcher.find()) {
            return Pair(1, standaloneMatcher.group(1)?.toIntOrNull() ?: 1)
        }

        return null
    }

    // ========================================================
    // BACKWARD COMPATIBILITY HELPERS
    // ========================================================

    suspend fun resolveCleanTitle(
        seriesName: String,
        seasonNum: Int,
        epNum: Int,
        rawFileName: String
    ): String {
        val epMeta = resolveEpisode(seriesName, 0L, seasonNum, epNum, rawFileName)
        return epMeta.officialTitle
    }

    fun cleanOfflineFileName(rawFileName: String, seriesName: String, epNum: Int): String {
        return cleanEpisodeTitleFromFileName(rawFileName, 1, epNum, seriesName)
    }

    private fun toTitleCase(input: String): String {
        val minorWords = setOf("a", "an", "the", "and", "but", "or", "for", "nor", "on", "at", "to", "from", "by", "of", "in")
        return input.split(" ").mapIndexed { index, word ->
            if (word.isBlank()) ""
            else {
                val lower = word.lowercase()
                if (index == 0 || lower !in minorWords) {
                    lower.replaceFirstChar { it.uppercase() }
                } else {
                    lower
                }
            }
        }.joinToString(" ")
    }
}
