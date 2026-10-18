package com.clotube.app.data

/**
 * Backward compatibility facade delegating to the unified OnlineMetadataResolver in data.metadata.
 */
object OnlineMetadataResolver {

    suspend fun resolveCleanTitle(
        seriesName: String,
        seasonNum: Int,
        epNum: Int,
        rawFileName: String
    ): String = com.clotube.app.data.metadata.OnlineMetadataResolver.resolveCleanTitle(
        seriesName = seriesName,
        seasonNum = seasonNum,
        epNum = epNum,
        rawFileName = rawFileName
    )

    suspend fun resolveEpisodeTitle(
        seriesName: String,
        seasonNumber: Int,
        episodeNumber: Int
    ): String? {
        val meta = com.clotube.app.data.metadata.OnlineMetadataResolver.resolveEpisode(
            seriesName = seriesName,
            tvMazeSeriesId = 0L,
            seasonNumber = seasonNumber,
            episodeNumber = episodeNumber,
            rawFileName = ""
        )
        return meta.officialTitle.ifBlank { null }
    }

    fun cleanOfflineFileName(rawFileName: String, seriesName: String, epNum: Int): String =
        com.clotube.app.data.metadata.OnlineMetadataResolver.cleanOfflineFileName(rawFileName, seriesName, epNum)

    fun isGenericTitle(rawTitle: String, seriesName: String, seasonNum: Int, epNum: Int): Boolean {
        val clean = cleanOfflineFileName(rawTitle, seriesName, epNum)
        return clean.equals("Episode $epNum", ignoreCase = true) || clean.isBlank()
    }

    suspend fun resolveMovie(rawFileName: String) =
        com.clotube.app.data.metadata.OnlineMetadataResolver.resolveMovie(rawFileName)

    suspend fun resolveSeries(rawSeriesName: String) =
        com.clotube.app.data.metadata.OnlineMetadataResolver.resolveSeries(rawSeriesName)

    suspend fun resolveEpisode(
        seriesName: String,
        tvMazeSeriesId: Long,
        seasonNumber: Int,
        episodeNumber: Int,
        rawFileName: String
    ) = com.clotube.app.data.metadata.OnlineMetadataResolver.resolveEpisode(
        seriesName = seriesName,
        tvMazeSeriesId = tvMazeSeriesId,
        seasonNumber = seasonNumber,
        episodeNumber = episodeNumber,
        rawFileName = rawFileName
    )

    fun clearCache() = com.clotube.app.data.metadata.OnlineMetadataResolver.clearCache()
}
