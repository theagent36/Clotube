package com.clotube.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineMetadataResolverTest {

    @Test
    fun testCleanOfflineFileName_ScrubbingNoise() {
        val series = "The Mentalist"

        // 1. Redundant show name + S06E01 tag with no descriptive words -> Episode 1
        assertEquals("Episode 1", OnlineMetadataResolver.cleanOfflineFileName("The Mentalist S06E01.mkv", series, 1))
        assertEquals("Episode 1", OnlineMetadataResolver.cleanOfflineFileName("The.Mentalist.S06E01.1080p.mkv", series, 1))
        assertEquals("Episode 1", OnlineMetadataResolver.cleanOfflineFileName("S06E01.mkv", series, 1))

        // 2. Redundant show name + tag + descriptive episode title -> extract ONLY the episode title
        assertEquals("The Desert Rose", OnlineMetadataResolver.cleanOfflineFileName("The Mentalist S06E01 The Desert Rose 1080p.mkv", series, 1))
        assertEquals("Black-Winged Redbird", OnlineMetadataResolver.cleanOfflineFileName("The.Mentalist.S06.E02.Black-Winged.Redbird.720p.WEB-DL.x264.mkv", series, 2))
        assertEquals("Wedding in Red", OnlineMetadataResolver.cleanOfflineFileName("S06E03 - Wedding in Red.mp4", series, 3))
        assertEquals("Red Listed", OnlineMetadataResolver.cleanOfflineFileName("The Mentalist - S06E04 - Red Listed [1080p BluRay]", series, 4))
    }

    @Test
    fun testResolveCleanTitle_TheMentalistSeason6OfficialTitles() = runBlocking {
        OnlineMetadataResolver.clearCache()

        val series = "The Mentalist"

        // S06E01 -> The Desert Rose
        val ep1 = OnlineMetadataResolver.resolveCleanTitle(series, 6, 1, "The Mentalist S06E01.mkv")
        assertEquals("The Desert Rose", ep1)

        // S06E02 -> Black-Winged Redbird
        val ep2 = OnlineMetadataResolver.resolveCleanTitle(series, 6, 2, "The Mentalist S06E02.mkv")
        assertEquals("Black-Winged Redbird", ep2)

        // S06E03 -> Wedding in Red
        val ep3 = OnlineMetadataResolver.resolveCleanTitle(series, 6, 3, "The Mentalist S06E03.mkv")
        assertEquals("Wedding in Red", ep3)

        // S06E04 -> Red Listed
        val ep4 = OnlineMetadataResolver.resolveCleanTitle(series, 6, 4, "The Mentalist S06E04.mkv")
        assertEquals("Red Listed", ep4)

        // S06E05 -> The Red Tattoo
        val ep5 = OnlineMetadataResolver.resolveCleanTitle(series, 6, 5, "The Mentalist S06E05.mkv")
        assertEquals("The Red Tattoo", ep5)

        // S06E06 -> Fire and Brimstone
        val ep6 = OnlineMetadataResolver.resolveCleanTitle(series, 6, 6, "The Mentalist S06E06.mkv")
        assertEquals("Fire and Brimstone", ep6)

        // Verify caching: second resolution is retrieved immediately from cache
        val ep1Cached = OnlineMetadataResolver.resolveCleanTitle(series, 6, 1, "The Mentalist S06E01.mkv")
        assertEquals("The Desert Rose", ep1Cached)
    }
}
