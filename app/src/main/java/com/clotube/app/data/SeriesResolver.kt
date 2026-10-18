package com.clotube.app.data

import com.clotube.app.model.Episode
import com.clotube.app.model.Season
import com.clotube.app.model.TvSeries
import java.util.Locale

/**
 * Utility helper for series title normalization.
 * All TV Series resolution is strictly handled by [SeriesRepository] enforcing
 * a 2-level folder hierarchy (Show Folder -> Season Folders -> Episode Files).
 */
object SeriesResolver {

    fun cleanTitle(input: String): String {
        return input.split(" ")
            .filter { it.isNotBlank() }
            .joinToString(" ") { word ->
                word.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
            }
    }
}
