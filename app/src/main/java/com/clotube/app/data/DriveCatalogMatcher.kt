package com.clotube.app.data

import com.clotube.app.model.Movie
import com.clotube.app.model.TvSeries

/**
 * Intelligent 4-Tier Drive Catalog Matching Engine.
 * Resolves movies and TV series across local Google Drive library items and global online
 * catalog metadata (TMDB, Cinemeta, IMDb) using exact ID, normalized multi-candidate,
 * token overlap, and bounded Levenshtein fuzzy matching.
 */
object DriveCatalogMatcher {

    /**
     * Extracts normalized comparison candidates from a [Movie] (title, fileName, parentFolder).
     */
    fun extractMovieCandidates(movie: Movie): List<String> {
        val candidates = mutableListOf<String>()

        val normTitle = WatchlistManager.normalizeTitle(movie.title)
        if (normTitle.isNotBlank()) candidates.add(normTitle)

        val normFileName = WatchlistManager.normalizeTitle(movie.fileName)
        if (normFileName.isNotBlank() && normFileName !in candidates) candidates.add(normFileName)

        val normFolder = WatchlistManager.normalizeTitle(movie.parentFolder)
        if (normFolder.isNotBlank() && normFolder !in candidates) candidates.add(normFolder)

        return candidates
    }

    /**
     * Extracts normalized comparison candidates from a [TvSeries] (title and season episode metadata).
     */
    fun extractSeriesCandidates(series: TvSeries): List<String> {
        val candidates = mutableListOf<String>()

        val normTitle = WatchlistManager.normalizeTitle(series.title)
        if (normTitle.isNotBlank()) candidates.add(normTitle)

        for (season in series.seasons) {
            for (episode in season.episodes) {
                if (episode.seriesTitle.isNotBlank()) {
                    val normSeriesTitle = WatchlistManager.normalizeTitle(episode.seriesTitle)
                    if (normSeriesTitle.isNotBlank() && normSeriesTitle !in candidates) {
                        candidates.add(normSeriesTitle)
                    }
                }
            }
        }

        return candidates
    }

    /**
     * Calculates the standard Levenshtein distance between two strings.
     */
    fun levenshteinDistance(s1: String, s2: String): Int {
        if (s1 == s2) return 0
        if (s1.isEmpty()) return s2.length
        if (s2.isEmpty()) return s1.length

        val prev = IntArray(s2.length + 1) { it }
        val curr = IntArray(s2.length + 1)

        for (i in 0 until s1.length) {
            curr[0] = i + 1
            for (j in 0 until s2.length) {
                val cost = if (s1[i] == s2[j]) 0 else 1
                curr[j + 1] = minOf(
                    curr[j] + 1,        // insertion
                    prev[j + 1] + 1,    // deletion
                    prev[j] + cost      // substitution
                )
            }
            for (j in 0..s2.length) {
                prev[j] = curr[j]
            }
        }
        return prev[s2.length]
    }

    /**
     * Computes similarity ratio between 0.0 (completely different) and 1.0 (identical).
     */
    fun similarityRatio(s1: String, s2: String): Double {
        val maxLen = maxOf(s1.length, s2.length)
        if (maxLen == 0) return 1.0
        val dist = levenshteinDistance(s1, s2)
        return (maxLen - dist).toDouble() / maxLen
    }

    /**
     * Checks if a target normalized title matches a candidate normalized string
     * via Tier 3 (token prefix / overlap) or Tier 4 (fuzzy Levenshtein).
     */
    private fun matchesTokenOrFuzzy(normTarget: String, candidateNorm: String): Boolean {
        if (normTarget.isBlank() || candidateNorm.isBlank()) return false
        if (normTarget == candidateNorm) return true

        // --- Tier 3: Token Prefix, Suffix, Boundary & Subset Overlap ---
        // A. Prefix / Suffix with word boundary
        if (candidateNorm.startsWith(normTarget) && (candidateNorm.length == normTarget.length || candidateNorm[normTarget.length] == ' ')) {
            return true
        }
        if (normTarget.startsWith(candidateNorm) && (normTarget.length == candidateNorm.length || normTarget[candidateNorm.length] == ' ')) {
            return true
        }

        val targetTokens = normTarget.split(" ").filter { it.isNotBlank() }
        val candidateTokens = candidateNorm.split(" ").filter { it.isNotBlank() }

        // B. Multi-word subset matching (e.g. "Dune Part Two" vs "Dune", "Breaking Bad" in "Breaking Bad Season 1")
        if (targetTokens.size >= 2 && candidateTokens.containsAll(targetTokens)) {
            return true
        }
        if (candidateTokens.size >= 2 && targetTokens.containsAll(candidateTokens)) {
            return true
        }

        if (targetTokens.size >= 2 && candidateTokens.size >= 2) {
            val intersection = targetTokens.intersect(candidateTokens.toSet()).size
            val minTokenSize = minOf(targetTokens.size, candidateTokens.size)
            if (minTokenSize >= 2 && (intersection.toDouble() / minTokenSize) >= 0.75) {
                return true
            }
        }

        // --- Tier 4: Bounded Levenshtein Fuzzy Match ---
        val minLen = minOf(normTarget.length, candidateNorm.length)
        val maxLen = maxOf(normTarget.length, candidateNorm.length)

        if (minLen >= 4) {
            val dist = levenshteinDistance(normTarget, candidateNorm)
            if (minLen in 4..6 && dist <= 1) return true
            if (minLen >= 7 && dist <= 2) return true
            if (maxLen >= 10 && dist <= 3 && ((maxLen - dist).toDouble() / maxLen) >= 0.80) return true
        }

        return false
    }

    /**
     * Finds a matching Drive [Movie] using the 4-tier pipeline:
     * 1. Exact ID match
     * 2. Exact normalized candidate match
     * 3. Token / prefix / overlap match
     * 4. Bounded Levenshtein fuzzy match
     */
    fun findMatchingDriveMovie(
        driveMovies: List<Movie>,
        targetId: String,
        targetTitle: String
    ): Movie? {
        if (driveMovies.isEmpty()) return null

        val cleanId = targetId.trim()
        val normTarget = WatchlistManager.normalizeTitle(targetTitle)

        // Tier 1: Exact Drive fileId match
        if (cleanId.isNotBlank() && !cleanId.startsWith("tmdb_") && !cleanId.startsWith("imdb_") && !cleanId.startsWith("ext_")) {
            val idMatch = driveMovies.firstOrNull {
                it.id == cleanId ||
                it.id == cleanId.removePrefix("drive_") ||
                cleanId == "drive_${it.id}"
            }
            if (idMatch != null) return idMatch
        }

        if (normTarget.isBlank()) return null

        // Cache candidate normalized lists for this pass
        val candidateMap = driveMovies.associateWith { extractMovieCandidates(it) }

        // Tier 2: Exact normalized match across any candidate (title, fileName, parentFolder)
        for ((movie, candidates) in candidateMap) {
            if (candidates.any { it == normTarget }) {
                return movie
            }
        }

        // Tier 3: Token Prefix / Overlap match
        for ((movie, candidates) in candidateMap) {
            for (cand in candidates) {
                // Prefix with word boundary or multi-token containment
                val candTokens = cand.split(" ").filter { it.isNotBlank() }
                val targetTokens = normTarget.split(" ").filter { it.isNotBlank() }

                if (cand.startsWith(normTarget) && (cand.length == normTarget.length || cand[normTarget.length] == ' ')) {
                    return movie
                }
                if (normTarget.startsWith(cand) && (normTarget.length == cand.length || normTarget[cand.length] == ' ')) {
                    return movie
                }
                if (targetTokens.size >= 2 && candTokens.containsAll(targetTokens)) {
                    return movie
                }
                if (candTokens.size >= 2 && targetTokens.containsAll(candTokens)) {
                    return movie
                }
                if (targetTokens.size >= 2 && candTokens.size >= 2) {
                    val intersection = targetTokens.intersect(candTokens.toSet()).size
                    val minTokenSize = minOf(targetTokens.size, candTokens.size)
                    if (minTokenSize >= 2 && (intersection.toDouble() / minTokenSize) >= 0.75) {
                        return movie
                    }
                }
            }
        }

        // Tier 4: Bounded Levenshtein fuzzy match (pick best match with distance <= threshold)
        var bestMovie: Movie? = null
        var bestSimilarity = 0.0
        val targetTrailingNum = Regex("""\b(\d+)$""").find(normTarget)?.groupValues?.getOrNull(1)

        for ((movie, candidates) in candidateMap) {
            for (cand in candidates) {
                val candTrailingNum = Regex("""\b(\d+)$""").find(cand)?.groupValues?.getOrNull(1)
                if (targetTrailingNum != null && candTrailingNum != null && targetTrailingNum != candTrailingNum) {
                    continue
                }
                val minLen = minOf(normTarget.length, cand.length)
                val maxLen = maxOf(normTarget.length, cand.length)
                if (minLen >= 4) {
                    val dist = levenshteinDistance(normTarget, cand)
                    val sim = (maxLen - dist).toDouble() / maxLen
                    val isMatch = (minLen in 4..6 && dist <= 1) ||
                                  (minLen >= 7 && dist <= 2) ||
                                  (maxLen >= 10 && dist <= 3 && sim >= 0.80)
                    if (isMatch && sim > bestSimilarity) {
                        bestSimilarity = sim
                        bestMovie = movie
                    }
                }
            }
        }

        return bestMovie
    }

    /**
     * Finds a matching Drive [TvSeries] using the 4-tier pipeline:
     * 1. Exact ID match
     * 2. Exact normalized candidate match
     * 3. Token / prefix / overlap match
     * 4. Bounded Levenshtein fuzzy match
     */
    fun findMatchingDriveSeries(
        driveSeries: List<TvSeries>,
        targetId: String,
        targetTitle: String
    ): TvSeries? {
        if (driveSeries.isEmpty()) return null

        val cleanId = targetId.trim()
        val normTarget = WatchlistManager.normalizeTitle(targetTitle)

        // Tier 1: Exact Drive folder ID match
        if (cleanId.isNotBlank() && !cleanId.startsWith("tmdb_") && !cleanId.startsWith("imdb_") && !cleanId.startsWith("ext_")) {
            val idMatch = driveSeries.firstOrNull {
                it.id == cleanId ||
                it.id == cleanId.removePrefix("drive_") ||
                cleanId == "drive_${it.id}"
            }
            if (idMatch != null) return idMatch
        }

        if (normTarget.isBlank()) return null

        // Cache candidate normalized lists for this pass
        val candidateMap = driveSeries.associateWith { extractSeriesCandidates(it) }

        // Tier 2: Exact normalized match across candidates
        for ((series, candidates) in candidateMap) {
            if (candidates.any { it == normTarget }) {
                return series
            }
        }

        // Tier 3: Token Prefix / Overlap match
        for ((series, candidates) in candidateMap) {
            for (cand in candidates) {
                val candTokens = cand.split(" ").filter { it.isNotBlank() }
                val targetTokens = normTarget.split(" ").filter { it.isNotBlank() }

                if (cand.startsWith(normTarget) && (cand.length == normTarget.length || cand[normTarget.length] == ' ')) {
                    return series
                }
                if (normTarget.startsWith(cand) && (normTarget.length == cand.length || normTarget[cand.length] == ' ')) {
                    return series
                }
                if (targetTokens.size >= 2 && candTokens.containsAll(targetTokens)) {
                    return series
                }
                if (candTokens.size >= 2 && targetTokens.containsAll(candTokens)) {
                    return series
                }
                if (targetTokens.size >= 2 && candTokens.size >= 2) {
                    val intersection = targetTokens.intersect(candTokens.toSet()).size
                    val minTokenSize = minOf(targetTokens.size, candTokens.size)
                    if (minTokenSize >= 2 && (intersection.toDouble() / minTokenSize) >= 0.75) {
                        return series
                    }
                }
            }
        }

        // Tier 4: Bounded Levenshtein fuzzy match
        var bestSeries: TvSeries? = null
        var bestSimilarity = 0.0

        for ((series, candidates) in candidateMap) {
            for (cand in candidates) {
                val minLen = minOf(normTarget.length, cand.length)
                val maxLen = maxOf(normTarget.length, cand.length)
                if (minLen >= 4) {
                    val dist = levenshteinDistance(normTarget, cand)
                    val sim = (maxLen - dist).toDouble() / maxLen
                    val isMatch = (minLen in 4..6 && dist <= 1) ||
                                  (minLen >= 7 && dist <= 2) ||
                                  (maxLen >= 10 && dist <= 3 && sim >= 0.80)
                    if (isMatch && sim > bestSimilarity) {
                        bestSimilarity = sim
                        bestSeries = series
                    }
                }
            }
        }

        return bestSeries
    }

    /**
     * Checks if a [Movie] matches a user's search query across raw text, normalized candidates,
     * or token prefixes.
     */
    fun matchesSearchQuery(movie: Movie, cleanQuery: String): Boolean {
        if (cleanQuery.isBlank()) return true

        val query = cleanQuery.trim()
        val normQuery = WatchlistManager.normalizeTitle(query)

        // Raw substring checks
        if (movie.title.contains(query, ignoreCase = true) ||
            movie.fileName.contains(query, ignoreCase = true) ||
            movie.parentFolder.contains(query, ignoreCase = true) ||
            movie.genre.contains(query, ignoreCase = true)
        ) {
            return true
        }

        // Normalized candidates check
        if (normQuery.isNotBlank()) {
            val candidates = extractMovieCandidates(movie)
            for (cand in candidates) {
                if (cand.contains(normQuery) || normQuery.contains(cand)) {
                    return true
                }
                if (matchesTokenOrFuzzy(normQuery, cand)) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * Checks if a [TvSeries] matches a user's search query across show title, seasons,
     * episode titles, episode filenames, or token prefixes.
     */
    fun matchesSearchQuery(series: TvSeries, cleanQuery: String): Boolean {
        if (cleanQuery.isBlank()) return true

        val query = cleanQuery.trim()
        val normQuery = WatchlistManager.normalizeTitle(query)

        // Raw substring checks on show title and genre
        if (series.title.contains(query, ignoreCase = true) ||
            series.genre.contains(query, ignoreCase = true) ||
            series.synopsis.contains(query, ignoreCase = true)
        ) {
            return true
        }

        // Check episodes within seasons
        val episodeMatch = series.seasons.any { season ->
            season.episodes.any { ep ->
                ep.displayTitle.contains(query, ignoreCase = true) ||
                ep.fileName.contains(query, ignoreCase = true) ||
                ep.seriesTitle.contains(query, ignoreCase = true)
            }
        }
        if (episodeMatch) return true

        // Normalized candidates check
        if (normQuery.isNotBlank()) {
            val candidates = extractSeriesCandidates(series)
            for (cand in candidates) {
                if (cand.contains(normQuery) || normQuery.contains(cand)) {
                    return true
                }
                if (matchesTokenOrFuzzy(normQuery, cand)) {
                    return true
                }
            }
        }

        return false
    }
}
