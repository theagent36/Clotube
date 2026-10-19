package com.clotube.app.model

import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import com.clotube.app.player.DriveMediaSourceFactory
import com.google.gson.annotations.SerializedName
import java.io.Serializable

/**
 * External subtitle track representation for sidecar captions.
 */
@Keep
@Immutable
data class ExternalSubtitleTrack(
    @SerializedName("fileId") val fileId: String,
    @SerializedName("fileName") val fileName: String,
    @SerializedName("languageCode") val languageCode: String,     // e.g., "en", "es", "und"
    @SerializedName("label") val label: String,            // e.g., "English (External)", "Spanish"
    @SerializedName("mimeType") val mimeType: String,         // e.g., MimeTypes.APPLICATION_SUBRIP
    @SerializedName("streamUri") val streamUri: String
) : Serializable

/**
 * Stable domain model representing a Google Drive video item with pre-calculated formatting.
 * Marked with @Immutable to guarantee Compose compiler stability and eliminate unnecessary recompositions.
 */
@Keep
@Immutable
data class VideoItem(
    @SerializedName("id") val id: String,
    @SerializedName("displayTitle") val displayTitle: String,
    @SerializedName("durationFormatted") val durationFormatted: String, // Pre-calculated (e.g., "02:10:24")
    @SerializedName("fileSizeFormatted") val fileSizeFormatted: String,  // Pre-calculated (e.g., "2.0 GB")
    @SerializedName("genreFormatted") val genreFormatted: String,     // Pre-calculated (e.g., "Action • Thriller")
    @SerializedName("resolutionBadge") val resolutionBadge: String,    // Pre-calculated (e.g., "1080p FHD")
    @SerializedName("streamUri") val streamUri: String,
    @SerializedName("thumbnailUrl") val thumbnailUrl: String,
    @SerializedName("fpsBadge") val fpsBadge: String = "",
    @SerializedName("mimeType") val mimeType: String = "video/mp4",
    @SerializedName("bitrateFormatted") val bitrateFormatted: String = "",
    @SerializedName("codec") val codec: String = "",
    @SerializedName("audioProfile") val audioProfile: String = "",
    @SerializedName("hdrBadge") val hdrBadge: String? = null,
    @SerializedName("width") val width: Int = 0,
    @SerializedName("height") val height: Int = 0,
    @SerializedName("durationMs") val durationMs: Long = 0L,
    @SerializedName("sizeBytes") val sizeBytes: Long = 0L,
    @SerializedName("externalSubtitles") val externalSubtitles: List<ExternalSubtitleTrack> = emptyList(),
    @SerializedName("watchProgressFraction") val watchProgressFraction: Float = 0.0f
) : Serializable {
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
