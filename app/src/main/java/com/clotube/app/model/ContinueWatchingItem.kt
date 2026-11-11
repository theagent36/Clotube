package com.clotube.app.model

import androidx.annotation.Keep
import androidx.compose.runtime.Immutable
import com.google.gson.annotations.SerializedName
import java.io.Serializable

@Keep
@Immutable
data class ContinueWatchingItem(
    @SerializedName("mediaId") val mediaId: String = "",
    @SerializedName("seriesId") val seriesId: String? = null,
    @SerializedName("isSeries") val isSeries: Boolean = false,
    @SerializedName("seriesTitle") val seriesTitle: String = "",
    @SerializedName("seasonNumber") val seasonNumber: Int = 0,
    @SerializedName("episodeNumber") val episodeNumber: Int = 0,
    @SerializedName("episodeTitle") val episodeTitle: String = "",
    @SerializedName("displaySubtitle") val displaySubtitle: String = "",
    @SerializedName("thumbnailUrl") val thumbnailUrl: String = "",
    @SerializedName("progressFraction") val progressFraction: Float = 0f,
    @SerializedName("currentPositionMs") val currentPositionMs: Long = 0L,
    @SerializedName("durationMs") val durationMs: Long = 0L,
    @SerializedName("lastWatchedTimestamp") val lastWatchedTimestamp: Long = 0L,
    @SerializedName("title") val title: String = seriesTitle.ifBlank { episodeTitle },
    @SerializedName("subtitle") val subtitle: String = displaySubtitle,
    @SerializedName("positionMs") val positionMs: Long = currentPositionMs,
    @SerializedName("timestamp") val timestamp: Long = lastWatchedTimestamp,
    @SerializedName("backdropUrl") val backdropUrl: String? = thumbnailUrl
) : Serializable
