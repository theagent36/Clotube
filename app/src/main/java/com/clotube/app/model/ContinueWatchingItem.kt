package com.clotube.app.model

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName
import java.io.Serializable

@Keep
data class ContinueWatchingItem(
    @SerializedName("mediaId") val mediaId: String = "",
    @SerializedName("seriesId") val seriesId: String? = null,
    @SerializedName("title") val title: String = "",
    @SerializedName("subtitle") val subtitle: String = "",
    @SerializedName("positionMs") val positionMs: Long = 0L,
    @SerializedName("durationMs") val durationMs: Long = 0L,
    @SerializedName("progressFraction") val progressFraction: Float = 0f,
    @SerializedName("isSeries") val isSeries: Boolean = false,
    @SerializedName("seasonNumber") val seasonNumber: Int? = null,
    @SerializedName("episodeNumber") val episodeNumber: Int? = null,
    @SerializedName("timestamp") val timestamp: Long = 0L,
    @SerializedName("thumbnailUrl") val thumbnailUrl: String? = null,
    @SerializedName("backdropUrl") val backdropUrl: String? = null
) : Serializable
