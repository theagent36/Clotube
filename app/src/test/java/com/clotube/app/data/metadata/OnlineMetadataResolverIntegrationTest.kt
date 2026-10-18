package com.clotube.app.data.metadata

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OnlineMetadataResolverIntegrationTest {

    @Before
    fun setUp() {
        OnlineMetadataResolver.clearCache()
    }

    @Test
    fun testRegexScrubbing_MovieQueriesAndYears() {
        val (q1, y1) = OnlineMetadataResolver.extractMovieQueryAndYear("Top Gun Maverick 2022 IMAX 1080p BluRay x264.mkv")
        assertEquals("Top Gun Maverick", q1)
        assertEquals("2022", y1)

        val (q2, y2) = OnlineMetadataResolver.extractMovieQueryAndYear("Crime 101 2026 [2160p HDR HEVC AAC].mp4")
        assertEquals("Crime 101", q2)
        assertEquals("2026", y2)

        val (q3, y3) = OnlineMetadataResolver.extractMovieQueryAndYear("Inception.2010.1080p.BluRay.x264.avi")
        assertEquals("Inception", q3)
        assertEquals("2010", y3)

        val (q4, y4) = OnlineMetadataResolver.extractMovieQueryAndYear("www.uindex.org - Dhurandhar (2025).mkv")
        assertEquals("Dhurandhar", q4)
        assertEquals("2025", y4)

        val (q5, y5) = OnlineMetadataResolver.extractMovieQueryAndYear("Www Uindex Org Dhurandhar (")
        assertEquals("Dhurandhar", q5)
        assertEquals(null, y5)

        val (q6, y6) = OnlineMetadataResolver.extractMovieQueryAndYear("Www Uindex Org Dhurandhar the Rev...")
        assertEquals("Dhurandhar the Rev", q6)
        assertEquals(null, y6)

        val (q7, y7) = OnlineMetadataResolver.extractMovieQueryAndYear("Www.Uindex.Org - Dhurandhar the Revenge (2026).mkv")
        assertEquals("Dhurandhar the Revenge", q7)
        assertEquals("2026", y7)
    }

    @Test
    fun testResolveMovie_TheVastOfNight() = runBlocking {
        val meta = OnlineMetadataResolver.resolveMovie("The.Vast.Of.Night.2020.1080p.AMZN.WEBRip.1400MB.DD5.1.x264-GalaxyRG.mkv")
        assertEquals("The Vast of Night", meta.officialTitle)
        assertTrue("Poster URL should not be blank", meta.posterUrl.isNotBlank())
        assertTrue("Poster should come from IMDb/Amazon CDN", meta.posterUrl.contains("media-amazon.com"))
    }

    @Test
    fun testResolveMovie_Dhurandhar() = runBlocking {
        val meta = OnlineMetadataResolver.resolveMovie("www.uindex.org - Dhurandhar (2025).mkv")
        assertEquals("Dhurandhar", meta.officialTitle)
        assertTrue("Poster URL should not be blank", meta.posterUrl.isNotBlank())
    }

    @Test
    fun testResolveMovie_DhurandharTheRevenge() = runBlocking {
        val meta = OnlineMetadataResolver.resolveMovie("Www.Uindex.Org - Dhurandhar the Revenge (2026).mkv")
        assertEquals("Dhurandhar: The Revenge", meta.officialTitle)
        assertTrue("Poster URL should not be blank", meta.posterUrl.isNotBlank())
    }

    @Test
    fun testRegexScrubbing_SeriesAndEpisodeNumbers() {
        val seriesClean = OnlineMetadataResolver.cleanSeriesTitle("The.Mentalist.Complete.Series.1080p.BluRay.x264")
        assertEquals("The Mentalist", seriesClean)

        val ep1 = OnlineMetadataResolver.parseSeasonAndEpisodeNumbers("The.Mentalist.S02E11.1080p.mkv")
        assertNotNull(ep1)
        assertEquals(2, ep1?.first)
        assertEquals(11, ep1?.second)

        val ep2 = OnlineMetadataResolver.parseSeasonAndEpisodeNumbers("The Mentalist - 2x12 - Bleeding Heart")
        assertNotNull(ep2)
        assertEquals(2, ep2?.first)
        assertEquals(12, ep2?.second)

        val ep3 = OnlineMetadataResolver.parseSeasonAndEpisodeNumbers("The Mentalist Episode 13")
        assertNotNull(ep3)
        assertEquals(1, ep3?.first)
        assertEquals(13, ep3?.second)
    }

    @Test
    fun testResolveMovie_TopGunMaverick() = runBlocking {
        val meta = OnlineMetadataResolver.resolveMovie("Top_Gun _Maverick_2022_IMAX_1080p_10bit_BluRay_x265_HEVC_Hindi_D.mkv")
        assertEquals("Top Gun: Maverick", meta.officialTitle)
        assertEquals("2022", meta.releaseYear)
        assertTrue("Poster URL should not be blank", meta.posterUrl.isNotBlank())
    }

    @Test
    fun testResolveMovie_Crime101() = runBlocking {
        val meta = OnlineMetadataResolver.resolveMovie("Crime 101 2026.mp4")
        assertEquals("Crime 101", meta.officialTitle)
        assertEquals("2026", meta.releaseYear)
    }

    @Test
    fun testResolveSeries_TheMentalist() = runBlocking {
        val series = OnlineMetadataResolver.resolveSeries("The Mentalist")
        assertEquals("The Mentalist", series.officialTitle)
        assertEquals(116L, series.tvMazeId)
        assertTrue("Genres should contain Drama", series.genres.contains("Drama"))
        assertTrue("Genres should contain Crime", series.genres.contains("Crime"))
        assertTrue("Poster URL should not be blank", series.posterUrl.isNotBlank())
    }

    @Test
    fun testResolveEpisode_TheMentalistSeason2Episodes() = runBlocking {
        // Resolve series first to populate TVMaze ID
        val series = OnlineMetadataResolver.resolveSeries("The Mentalist")
        val showId = series.tvMazeId
        assertEquals(116L, showId)

        // S02E11 -> Rose-Colored Glasses
        val ep11 = OnlineMetadataResolver.resolveEpisode(
            seriesName = "The Mentalist",
            tvMazeSeriesId = showId,
            seasonNumber = 2,
            episodeNumber = 11,
            rawFileName = "The.Mentalist.S02E11.mkv"
        )
        assertEquals("Rose-Colored Glasses", ep11.officialTitle)
        assertTrue("Episode 11 thumbnail should not be blank", ep11.thumbnailUrl.isNotBlank())

        // S02E12 -> Bleeding Heart
        val ep12 = OnlineMetadataResolver.resolveEpisode(
            seriesName = "The Mentalist",
            tvMazeSeriesId = showId,
            seasonNumber = 2,
            episodeNumber = 12,
            rawFileName = "The.Mentalist.S02E12.mkv"
        )
        assertEquals("Bleeding Heart", ep12.officialTitle)
        assertTrue("Episode 12 thumbnail should not be blank", ep12.thumbnailUrl.isNotBlank())

        // S02E13 -> Redline
        val ep13 = OnlineMetadataResolver.resolveEpisode(
            seriesName = "The Mentalist",
            tvMazeSeriesId = showId,
            seasonNumber = 2,
            episodeNumber = 13,
            rawFileName = "The.Mentalist.S02E13.mkv"
        )
        assertEquals("Redline", ep13.officialTitle)
        assertTrue("Episode 13 thumbnail should not be blank", ep13.thumbnailUrl.isNotBlank())

        // S02E14 -> Blood In, Blood Out
        val ep14 = OnlineMetadataResolver.resolveEpisode(
            seriesName = "The Mentalist",
            tvMazeSeriesId = showId,
            seasonNumber = 2,
            episodeNumber = 14,
            rawFileName = "The.Mentalist.S02E14.mkv"
        )
        assertEquals("Blood In, Blood Out", ep14.officialTitle)
        assertTrue("Episode 14 thumbnail should not be blank", ep14.thumbnailUrl.isNotBlank())
    }
}
