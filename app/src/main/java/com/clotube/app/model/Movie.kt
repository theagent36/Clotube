package com.clotube.app.model

import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import com.clotube.app.player.DriveMediaSourceFactory
import com.google.gson.annotations.SerializedName
import java.io.Serializable

/**
 * Domain model representing a discovered movie file in Google Drive.
 * Marked with @Immutable to guarantee Compose compiler skipping and stability.
 */
@Keep
@Immutable
data class Movie(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("fileName") val fileName: String,
    @SerializedName("fileSize") val fileSize: String,
    @SerializedName("mimeType") val mimeType: String = "video/mp4",
    @SerializedName("thumbnailUrl") val thumbnailUrl: String? = null,
    @SerializedName("parentFolder") val parentFolder: String = "",
    @SerializedName("durationFormatted") val durationFormatted: String = "00:00",
    @SerializedName("resolutionBadge") val resolutionBadge: String = "HD",
    @SerializedName("genre") val genre: String = "Movie • Cinema",
    @SerializedName("streamUri") val streamUri: String = DriveMediaSourceFactory.buildDriveStreamUri(id).toString(),
    @SerializedName("sizeBytes") val sizeBytes: Long = 0L
) : Serializable {
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
        title: String,
        durationMs: Long = 0L,
        formattedDuration: String = "00:00",
        resolution: String = "1080p FHD",
        thumbnailUrl: String? = null,
        fileName: String = title,
        fileSize: String = "",
        mimeType: String = "video/mp4",
        parentFolder: String = "",
        genre: String = "Movie • Cinema",
        streamUri: String = DriveMediaSourceFactory.buildDriveStreamUri(id).toString(),
        sizeBytes: Long = 0L
    ) : this(
        id = id,
        title = title,
        fileName = fileName,
        fileSize = fileSize,
        mimeType = mimeType,
        thumbnailUrl = thumbnailUrl,
        parentFolder = parentFolder,
        durationFormatted = formattedDuration,
        resolutionBadge = resolution,
        genre = genre,
        streamUri = streamUri,
        sizeBytes = sizeBytes
    )

    /**
     * Bridges Movie to VideoItem for backward compatibility with existing player and catalog components.
     */
    fun toVideoItem(): VideoItem {
        return VideoItem(
            id = id,
            title = title,
            sizeFormatted = fileSize,
            durationFormatted = durationFormatted,
            resolutionBadge = resolutionBadge,
            mimeType = mimeType,
            thumbnailUrl = thumbnailUrl,
            genre = genre,
            streamUri = streamUri,
            sizeBytes = sizeBytes
        )
    }
}
