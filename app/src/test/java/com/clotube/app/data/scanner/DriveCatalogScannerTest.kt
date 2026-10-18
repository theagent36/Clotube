package com.clotube.app.data.scanner

import com.google.api.services.drive.model.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DriveCatalogScannerTest {

    @Test
    fun testParseSeasonAndEpisode_StandardFormat() {
        val (season, episode) = DriveCatalogScanner.parseSeasonAndEpisode("The.Mentalist.S01E08.1080p.mkv", "The Mentalist")
        assertEquals(1, season)
        assertEquals(8, episode)
    }

    @Test
    fun testParseSeasonAndEpisode_XFormat() {
        val (season, episode) = DriveCatalogScanner.parseSeasonAndEpisode("Breaking.Bad.1x08.mkv", "Breaking Bad")
        assertEquals(1, season)
        assertEquals(8, episode)
    }

    @Test
    fun testParseSeasonAndEpisode_SeasonFolderWithEpisodeFile() {
        val (season, episode) = DriveCatalogScanner.parseSeasonAndEpisode("Episode 8.mkv", "Season 1")
        assertEquals(1, season)
        assertEquals(8, episode)
    }

    @Test
    fun testParseSeasonAndEpisode_DashFormat() {
        val (season, episode) = DriveCatalogScanner.parseSeasonAndEpisode("Show - S1 - 08.mkv", "Show")
        assertEquals(1, season)
        assertEquals(8, episode)
    }

    @Test
    fun testParseSeasonAndEpisode_SeasonFolderWithStandaloneNumber() {
        val (season, episode) = DriveCatalogScanner.parseSeasonAndEpisode("08.mkv", "Season 2")
        assertEquals(2, season)
        assertEquals(8, episode)
    }

    @Test
    fun testCleanMovieTitle_NestedGenericName() {
        val title = DriveCatalogScanner.cleanMovieTitle("movie.mkv", "Glass Onion")
        assertEquals("Glass Onion", title)
    }

    @Test
    fun testCleanMovieTitle_DottedFileName() {
        val title = DriveCatalogScanner.cleanMovieTitle("Glass.Onion.2022.mkv", "Movies")
        assertEquals("Glass Onion 2022", title)
    }

    @Test
    fun testResolveTargetId_Shortcut() {
        val file = File().apply {
            id = "shortcut-id"
            mimeType = "application/vnd.google-apps.shortcut"
            shortcutDetails = File.ShortcutDetails().apply {
                targetId = "target-file-123"
            }
        }
        val resolved = DriveCatalogScanner.resolveTargetId(file)
        assertEquals("target-file-123", resolved)
    }

    @Test
    fun testResolveTargetId_RegularFile() {
        val file = File().apply {
            id = "direct-file-123"
            mimeType = "video/mp4"
        }
        val resolved = DriveCatalogScanner.resolveTargetId(file)
        assertEquals("direct-file-123", resolved)
    }

    @Test
    fun testIsVideoFile_SupportedExtensions() {
        val mkv = File().apply { name = "sample.mkv" }
        val mp4 = File().apply { name = "sample.mp4" }
        val webm = File().apply { name = "sample.webm" }
        val txt = File().apply { name = "notes.txt" }

        assertTrue(DriveCatalogScanner.isVideoFile(mkv))
        assertTrue(DriveCatalogScanner.isVideoFile(mp4))
        assertTrue(DriveCatalogScanner.isVideoFile(webm))
        assertFalse(DriveCatalogScanner.isVideoFile(txt))
    }

    @Test
    fun testFormatFileSize() {
        assertEquals("1.5 GB", DriveCatalogScanner.formatFileSize(1500L * 1024 * 1024))
        assertEquals("750 MB", DriveCatalogScanner.formatFileSize(750L * 1024 * 1024))
    }
}
