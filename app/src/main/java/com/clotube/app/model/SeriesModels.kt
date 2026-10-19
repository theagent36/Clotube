package com.clotube.app.model

import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import com.google.gson.annotations.SerializedName
import java.io.Serializable
import java.util.Locale

/**
 * Episode data model representing an individual TV show episode.
 * Marked with @Immutable to enable Compose compiler skipping during scroll.
 */
@Keep
@Immutable
data class Episode(
    @SerializedName("id") val id: String, // Google Drive fileId
    @SerializedName("seasonNumber") val seasonNumber: Int,
    @SerializedName("episodeNumber") val episodeNumber: Int,
    @SerializedName("episodeTagFormatted") val episodeTagFormatted: String, // "S01:E01"
    @SerializedName("displayTitle") val displayTitle: String,
    @SerializedName("durationFormatted") val durationFormatted: String,
    @SerializedName("fileSizeFormatted") val fileSizeFormatted: String,
    @SerializedName("resolutionBadge") val resolutionBadge: String,
    @SerializedName("streamUri") val streamUri: String,
    @SerializedName("thumbnailUrl") val thumbnailUrl: String,
    @SerializedName("watchProgressFraction") val watchProgressFraction: Float = 0.0f, // 0.0 to 1.0
    @SerializedName("overview") val overview: String = "",
    @SerializedName("externalSubtitles") val externalSubtitles: List<ExternalSubtitleTrack> = emptyList(),
    @SerializedName("width") val width: Int = 0,
    @SerializedName("height") val height: Int = 0,
    @SerializedName("fileName") val fileName: String = "",
    @SerializedName("seriesTitle") val seriesTitle: String = "",
    @SerializedName("sizeBytes") val sizeBytes: Long = 0L
) : Serializable {
    val title: String get() = displayTitle
    val sizeFormatted: String get() = fileSizeFormatted
    val fileSize: String get() = fileSizeFormatted
    val formattedDuration: String get() = durationFormatted
    val resolution: String get() = resolutionBadge
    val durationMs: Long
        get() {
            return try {
                val parts = durationFormatted.split(":")
                when (parts.size) {
                    3 -> (parts[0].trim().toLong() * 3600 + parts[1].trim().toLong() * 60 + parts[2].trim().toLong()) * 1000L
                    2 -> (parts[0].trim().toLong() * 60 + parts[1].trim().toLong()) * 1000L
                    1 -> (parts[0].trim().toLongOrNull() ?: 0L) * 1000L
                    else -> 0L
                }
            } catch (e: Exception) {
                0L
            }
        }

    constructor(
        id: String,
        seriesTitle: String = "",
        displayTitle: String,
        seasonNumber: Int,
        episodeNumber: Int,
        fileName: String = "",
        fileSize: String = "",
        thumbnailUrl: String? = null,
        durationFormatted: String = "42:00",
        streamUri: String = com.clotube.app.player.DriveMediaSourceFactory.buildDriveStreamUri(id).toString(),
        resolutionBadge: String = "HD",
        watchProgressFraction: Float = 0.0f,
        sizeBytes: Long = 0L
    ) : this(
        id = id,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        episodeTagFormatted = String.format(Locale.US, "S%02d:E%02d", seasonNumber, episodeNumber),
        displayTitle = displayTitle,
        durationFormatted = durationFormatted,
        fileSizeFormatted = fileSize,
        resolutionBadge = resolutionBadge,
        streamUri = streamUri,
        thumbnailUrl = thumbnailUrl.orEmpty(),
        watchProgressFraction = watchProgressFraction,
        fileName = fileName,
        seriesTitle = seriesTitle,
        sizeBytes = sizeBytes
    )

    constructor(
        id: String,
        episodeNumber: Int,
        seasonNumber: Int,
        title: String,
        durationFormatted: String,
        thumbnailUrl: String? = null,
        streamUri: String,
        watchProgressFraction: Float = 0.0f,
        sizeFormatted: String = "",
        overview: String = "",
        externalSubtitles: List<ExternalSubtitleTrack> = emptyList(),
        resolutionBadge: String = "HD",
        width: Int = 0,
        height: Int = 0,
        episodeTagFormatted: String = String.format(Locale.US, "S%02d:E%02d", seasonNumber, episodeNumber),
        sizeBytes: Long = 0L
    ) : this(
        id = id,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        episodeTagFormatted = episodeTagFormatted,
        displayTitle = title,
        durationFormatted = durationFormatted,
        fileSizeFormatted = sizeFormatted,
        resolutionBadge = resolutionBadge,
        streamUri = streamUri,
        thumbnailUrl = thumbnailUrl.orEmpty(),
        watchProgressFraction = watchProgressFraction,
        overview = overview,
        externalSubtitles = externalSubtitles,
        width = width,
        height = height,
        sizeBytes = sizeBytes
    )

    constructor(
        id: String,
        seasonNumber: Int,
        episodeNumber: Int,
        displayTitle: String,
        formattedDuration: String,
        fileSize: String,
        resolution: String,
        thumbnailUrl: String
    ) : this(
        id = id,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        episodeTagFormatted = String.format(Locale.US, "S%02d:E%02d", seasonNumber, episodeNumber),
        displayTitle = displayTitle,
        durationFormatted = formattedDuration,
        fileSizeFormatted = fileSize,
        resolutionBadge = resolution,
        streamUri = com.clotube.app.player.DriveMediaSourceFactory.buildDriveStreamUri(id).toString(),
        thumbnailUrl = thumbnailUrl
    )
}

/**
 * Season model grouping episodes of a specific season.
 * Marked with @Immutable for Compose stability across nested hierarchies.
 */
@Keep
@Immutable
data class Season(
    @SerializedName("id") val id: String = "", // Google Drive folder ID for this season
    @SerializedName("seasonNumber") val seasonNumber: Int,
    @SerializedName("seasonTitle") val seasonTitle: String = "Season $seasonNumber", // e.g. "Season 1"
    @SerializedName("episodes") val episodes: List<Episode> = emptyList()
) : Serializable {
    val title: String get() = seasonTitle

    constructor(
        seasonNumber: Int,
        episodes: List<Episode> = emptyList()
    ) : this(
        id = "",
        seasonNumber = seasonNumber,
        seasonTitle = "Season $seasonNumber",
        episodes = episodes
    )
}

/**
 * TvSeries model representing a full multi-season television show.
 * Marked with @Immutable to guarantee recomposition skipping during list scrolling.
 */
@Keep
@Immutable
data class TvSeries(
    @SerializedName("id") val id: String, // Google Drive folder ID for this show
    @SerializedName("title") val title: String, // e.g. "The Mentalist"
    @SerializedName("genreFormatted") val genreFormatted: String,
    @SerializedName("totalSeasonsFormatted") val totalSeasonsFormatted: String,
    @SerializedName("seasons") val seasons: List<Season>,
    @SerializedName("thumbnailUrl") val thumbnailUrl: String,
    @SerializedName("backdropUrl") val backdropUrl: String? = null,
    @SerializedName("posterUrl") val posterUrl: String? = null,
    @SerializedName("synopsis") val synopsis: String = "High-bitrate OTT Series streamed directly from your Google Drive Series folder.",
    @SerializedName("qualityBadge") val qualityBadge: String = "Direct Stream • HD",
    @SerializedName("ratingBadge") val ratingBadge: String = "TV-14",
    @SerializedName("watchProgressFraction") val watchProgressFraction: Float = 0.0f
) : Serializable {
    val genre: String get() = genreFormatted
    val genreBadge: String get() = genreFormatted
    val totalEpisodes: Int get() = seasons.sumOf { it.episodes.size }

    constructor(
        id: String,
        title: String,
        posterUrl: String? = null,
        genre: String = "TV Series • Drama",
        seasons: List<Season> = emptyList(),
        totalEpisodes: Int = seasons.sumOf { it.episodes.size },
        backdropUrl: String? = posterUrl,
        thumbnailUrl: String? = posterUrl
    ) : this(
        id = id,
        title = title,
        genreFormatted = genre,
        totalSeasonsFormatted = if (seasons.size == 1) "1 Season" else "${seasons.size} Seasons",
        seasons = seasons,
        thumbnailUrl = (posterUrl ?: backdropUrl).orEmpty(),
        backdropUrl = backdropUrl,
        posterUrl = posterUrl
    )

    constructor(
        id: String,
        title: String,
        backdropUrl: String? = null,
        posterUrl: String? = null,
        seasons: List<Season> = emptyList(),
        synopsis: String = "High-bitrate OTT Series streamed directly from your Google Drive Series folder.",
        qualityBadge: String = "Direct Stream • HD",
        ratingBadge: String = "TV-14",
        genre: String = "TV Series • Drama",
        genreBadge: String = genre,
        totalSeasonsFormatted: String = if (seasons.size == 1) "1 Season" else "${seasons.size} Seasons",
        watchProgressFraction: Float = 0.0f,
        thumbnailUrl: String? = backdropUrl ?: posterUrl ?: ""
    ) : this(
        id = id,
        title = title,
        genreFormatted = genre,
        totalSeasonsFormatted = totalSeasonsFormatted,
        seasons = seasons,
        thumbnailUrl = thumbnailUrl.orEmpty(),
        backdropUrl = backdropUrl,
        posterUrl = posterUrl,
        synopsis = synopsis,
        qualityBadge = qualityBadge,
        ratingBadge = ratingBadge,
        watchProgressFraction = watchProgressFraction
    )
}

typealias Series = TvSeries

