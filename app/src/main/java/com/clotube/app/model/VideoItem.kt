package com.clotube.app.model

import androidx.compose.runtime.Immutable
import com.clotube.app.player.DriveMediaSourceFactory

/**
 * External subtitle track representation for sidecar captions.
 */
@Immutable
data class ExternalSubtitleTrack(
    val fileId: String,
    val fileName: String,
    val languageCode: String,     // e.g., "en", "es", "und"
    val label: String,            // e.g., "English (External)", "Spanish"
    val mimeType: String,         // e.g., MimeTypes.APPLICATION_SUBRIP
    val streamUri: String
)

/**
 * Stable domain model representing a Google Drive video item with pre-calculated formatting.
 * Marked with @Immutable to guarantee Compose compiler stability and eliminate unnecessary recompositions.
 */
@Immutable
data class VideoItem(
    val id: String,
    val displayTitle: String,
    val durationFormatted: String, // Pre-calculated (e.g., "02:10:24")
    val fileSizeFormatted: String,  // Pre-calculated (e.g., "2.0 GB")
    val genreFormatted: String,     // Pre-calculated (e.g., "Action • Thriller")
    val resolutionBadge: String,    // Pre-calculated (e.g., "1080p FHD")
    val streamUri: String,
    val thumbnailUrl: String,
    val fpsBadge: String = "",
    val mimeType: String = "video/mp4",
    val bitrateFormatted: String = "",
    val codec: String = "",
    val audioProfile: String = "",
    val hdrBadge: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0L,
    val sizeBytes: Long = 0L,
    val externalSubtitles: List<ExternalSubtitleTrack> = emptyList(),
    val watchProgressFraction: Float = 0.0f
) {
    val title: String get() = displayTitle
    val fileId: String get() = id
    val sizeFormatted: String get() = fileSizeFormatted
    val genre: String get() = genreFormatted

    constructor(
        id: String,
        title: String,
        sizeFormatted: String = "",
        durationFormatted: String = "00:00",
        resolutionBadge: String = "HD",
        fpsBadge: String = "",
        mimeType: String = "video/mp4",
        thumbnailUrl: String? = null,
        bitrateFormatted: String = "",
        codec: String = "",
        audioProfile: String = "",
        hdrBadge: String? = null,
        width: Int = 0,
        height: Int = 0,
        durationMs: Long = 0L,
        sizeBytes: Long = 0L,
        externalSubtitles: List<ExternalSubtitleTrack> = emptyList(),
        genre: String = "Movie • Cinema",
        watchProgressFraction: Float = 0.0f,
        streamUri: String = DriveMediaSourceFactory.buildDriveStreamUri(id).toString()
    ) : this(
        id = id,
        displayTitle = title,
        durationFormatted = durationFormatted,
        fileSizeFormatted = sizeFormatted,
        genreFormatted = genre,
        resolutionBadge = resolutionBadge,
        streamUri = streamUri,
        thumbnailUrl = thumbnailUrl.orEmpty(),
        fpsBadge = fpsBadge,
        mimeType = mimeType,
        bitrateFormatted = bitrateFormatted,
        codec = codec,
        audioProfile = audioProfile,
        hdrBadge = hdrBadge,
        width = width,
        height = height,
        durationMs = durationMs,
        sizeBytes = sizeBytes,
        externalSubtitles = externalSubtitles,
        watchProgressFraction = watchProgressFraction
    )
}
