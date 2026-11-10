package com.clotube.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryModelsV2Test {

    @Test
    fun roundTripSerialization_preservesAllFields() {
        val payload = WatchHistoryPayloadV2(
            version = 2,
            updatedAt = 1_700_000_000_000L,
            active = listOf(
                HistoryItemEntityV2(
                    id = "ep_101",
                    title = "Pilot",
                    positionMs = 120_000L,
                    durationMs = 2_400_000L,
                    timestamp = 1_700_000_000_000L,
                    isWatched = false,
                    seriesId = "series_abc",
                    seasonNumber = 1,
                    episodeNumber = 1
                )
            ),
            resume = mapOf(
                "series_abc" to SeriesResumeEntityV2(
                    episodeId = "ep_101",
                    seasonNumber = 1,
                    episodeNumber = 1,
                    positionMs = 120_000L,
                    durationMs = 2_400_000L,
                    timestamp = 1_700_000_000_000L
                )
            ),
            watchedIds = listOf("ep_100", "movie_99")
        )

        val json = payload.toJsonString()
        val parsed = WatchHistoryPayloadV2.fromJsonString(json)

        assertEquals(2, parsed.version)
        assertEquals(1_700_000_000_000L, parsed.updatedAt)
        assertEquals(1, parsed.active.size)
        assertEquals("ep_101", parsed.active[0].id)
        assertEquals("Pilot", parsed.active[0].title)
        assertEquals("series_abc", parsed.active[0].seriesId)
        assertEquals(1, parsed.resume.size)
        assertEquals("ep_101", parsed.resume["series_abc"]?.episodeId)
        assertEquals(listOf("ep_100", "movie_99"), parsed.watchedIds)
        assertTrue(AndroidWatchHistoryManager.isV2Payload(json))
    }

    @Test
    fun buildV2PayloadFromEntries_migratesFlatAndLegacyKeys() {
        val entries = mapOf(
            "ep_1_title" to AndroidTypedEntry("string", "Red John"),
            "ep_1_pos" to AndroidTypedEntry("long", 500_000L),
            "ep_1_dur" to AndroidTypedEntry("long", 2_500_000L),
            "ep_1_fraction" to AndroidTypedEntry("float", 0.2f),
            "ep_1_timestamp" to AndroidTypedEntry("long", 5000L),
            "ep_1_is_watched" to AndroidTypedEntry("boolean", false),
            "ep_1_series_id" to AndroidTypedEntry("string", "ser_1"),
            "ep_1_season_num" to AndroidTypedEntry("int", 1),
            "ep_1_episode_num" to AndroidTypedEntry("int", 2),
            "series_resume_ser_1_episode_id" to AndroidTypedEntry("string", "ep_1"),
            "series_resume_ser_1_season_num" to AndroidTypedEntry("int", 1),
            "series_resume_ser_1_episode_num" to AndroidTypedEntry("int", 2),
            "series_resume_ser_1_pos_ms" to AndroidTypedEntry("long", 500_000L),
            "series_resume_ser_1_dur_ms" to AndroidTypedEntry("long", 2_500_000L),
            "series_resume_ser_1_timestamp" to AndroidTypedEntry("long", 5000L),
            "completed_legacy_mov" to AndroidTypedEntry("boolean", true),
            "playback_pos_legacy_mov" to AndroidTypedEntry("long", 1_000_000L),
            "playback_dur_legacy_mov" to AndroidTypedEntry("long", 1_000_000L),
            "timestamp_legacy_mov" to AndroidTypedEntry("long", 4000L)
        )

        val payload = AndroidWatchHistoryManager.buildV2PayloadFromEntries(entries)

        assertTrue(payload.watchedIds.contains("legacy_mov"))
        assertFalse(payload.watchedIds.contains("ep_1"))
        assertEquals("ep_1", payload.resume["ser_1"]?.episodeId)
        assertTrue(payload.active.any { it.id == "ep_1" && it.positionMs == 500_000L })
    }

    @Test
    fun prunedToMaxBytes_keepsPayloadUnder30KbWhilePreservingWatchedIds() {
        val manyActive = (1..350).map { i ->
            HistoryItemEntityV2(
                id = "drive_file_identifier_number_$i",
                title = "Long Episode Title For Stress Testing Active Window Pruning Number $i",
                positionMs = if (i > 300) 450_000L else 2_400_000L,
                durationMs = 2_400_000L,
                timestamp = 1_700_000_000_000L + i,
                isWatched = i <= 300,
                seriesId = "series_drive_folder_id_1",
                seasonNumber = 1,
                episodeNumber = i
            )
        }
        val allWatched = (1..300).map { "drive_file_identifier_number_$it" }

        val payload = WatchHistoryPayloadV2(
            version = 2,
            updatedAt = 1_700_000_500_000L,
            active = manyActive,
            resume = mapOf(
                "series_drive_folder_id_1" to SeriesResumeEntityV2(
                    episodeId = "drive_file_identifier_number_350",
                    seasonNumber = 1,
                    episodeNumber = 350,
                    positionMs = 450_000L,
                    durationMs = 2_400_000L,
                    timestamp = 1_700_000_000_350L
                )
            ),
            watchedIds = allWatched
        ).prunedToMaxBytes()

        val bytes = payload.toJsonString().toByteArray(Charsets.UTF_8).size
        assertTrue("Expected payload < 30 KB, got $bytes bytes", bytes < 30 * 1024)
        assertEquals(300, payload.watchedIds.size)
        assertEquals("drive_file_identifier_number_350", payload.active.first().id)
    }

    @Test
    fun isV2Payload_distinguishesV1FromV2() {
        val v1 = """{"version":1,"updatedAt":1234,"entries":{"ep1_pos":{"type":"long","value":100}}}"""
        assertFalse(AndroidWatchHistoryManager.isV2Payload(v1))
    }
}

