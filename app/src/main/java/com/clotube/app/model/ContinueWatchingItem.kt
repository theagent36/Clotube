package com.clotube.app.model

data class ContinueWatchingItem(
    val mediaId: String,
    val seriesId: String? = null,
    val title: String,
    val subtitle: String,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val progressFraction: Float = 0f,
    val isSeries: Boolean = false,
    val seasonNumber: Int? = null,
    val episodeNumber: Int? = null,
    val timestamp: Long = 0L,
    val thumbnailUrl: String? = null,
    val backdropUrl: String? = null
)
