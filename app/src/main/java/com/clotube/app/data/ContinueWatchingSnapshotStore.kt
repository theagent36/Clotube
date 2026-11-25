package com.clotube.app.data

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import com.clotube.app.data.metadata.OnlineMetadataResolver
import com.clotube.app.viewmodel.ContinueWatchingDisplayData
import com.google.android.gms.auth.api.signin.GoogleSignIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Single-record, profile-scoped local-first Continue Watching snapshot store.
 *
 * Persists only the latest known-good [ContinueWatchingDisplayData] (or an authoritative
 * confirmed-empty marker) to `files/profiles/<sanitized_email>/cw_hero_snapshot.json`
 * so cold startup can hydrate the Hero slot in 0-15ms before OAuth or Google Drive completes.
 */
object ContinueWatchingSnapshotStore {

    private const val TAG = "CwSnapshotStore"
    private const val SNAPSHOT_FILE_NAME = "cw_hero_snapshot.json"

    private val ioMutex = Mutex()

    @Keep
    data class LoadedSnapshotResult(
        val item: ContinueWatchingDisplayData?,
        val updatedAt: Long,
        val confirmedEmpty: Boolean
    )

    private data class RawSnapshotRecord(
        val mediaId: String,
        val seriesId: String?,
        val isSeries: Boolean,
        val displayHeader: String,
        val displayTitle: String,
        val seriesTitle: String,
        val seasonNumber: Int,
        val episodeNumber: Int,
        val episodeTitle: String,
        val canonicalArtworkUrl: String,
        val progressFraction: Float,
        val currentPositionMs: Long,
        val durationMs: Long,
        val updatedAt: Long,
        val confirmedEmpty: Boolean
    )

    suspend fun resolveActiveProfileEmail(context: Context): String? = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        runCatching { ProfileManager.ensureLoaded(appCtx) }
        ProfileManager.activeProfileEmail.value?.takeIf { it.isNotBlank() }?.let { return@withContext it }
        if (runCatching { ByocConfigManager.hasActiveByocSession(appCtx) }.getOrDefault(false)) {
            runCatching { ByocConfigManager.getSession(appCtx)?.userEmail }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let { return@withContext it }
        }
        runCatching { GoogleSignIn.getLastSignedInAccount(appCtx)?.email }.getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    private suspend fun getSnapshotFile(context: Context, profileEmail: String?): File {
        val appCtx = context.applicationContext
        val effectiveEmail = profileEmail?.takeIf { it.isNotBlank() } ?: resolveActiveProfileEmail(appCtx)
        val profileDir = ProfileManager.getProfileDir(appCtx, effectiveEmail)
        return File(profileDir, SNAPSHOT_FILE_NAME)
    }

    private fun readRawRecord(file: File): RawSnapshotRecord? {
        if (!file.exists() || file.length() <= 0L) return null
        return try {
            val raw = file.readText(Charsets.UTF_8)
            if (raw.isBlank()) return null
            val root = JSONObject(raw)
            val mediaId = root.optString("mediaId", "").trim()
            val seriesId = root.optString("seriesId", "").trim().ifBlank { null }
            val isSeries = root.optBoolean("isSeries", true)
            val displayHeader = root.optString("displayHeader", "")
            val displayTitle = root.optString("displayTitle", "")
            val seriesTitle = root.optString("seriesTitle", displayHeader)
            val seasonNumber = root.optInt("seasonNumber", 0)
            val episodeNumber = root.optInt("episodeNumber", 0)
            val episodeTitle = root.optString("episodeTitle", displayTitle)
            val rawArt = root.optString("canonicalArtworkUrl", "").trim()
            val canonicalArtworkUrl = if (OnlineMetadataResolver.isDriveVideoFrameUrl(rawArt)) "" else rawArt
            val progressFraction = root.optDouble("progressFraction", 0.0).toFloat().coerceIn(0f, 1f)
            val currentPositionMs = root.optLong("currentPositionMs", 0L).coerceAtLeast(0L)
            val durationMs = root.optLong("durationMs", 0L).coerceAtLeast(0L)
            val updatedAt = WatchHistoryPayloadV2.normalizeTimestampMs(root.optLong("updatedAt", 0L))
            val confirmedEmpty = root.optBoolean("confirmedEmpty", false)

            RawSnapshotRecord(
                mediaId = mediaId,
                seriesId = seriesId,
                isSeries = isSeries,
                displayHeader = displayHeader,
                displayTitle = displayTitle,
                seriesTitle = seriesTitle,
                seasonNumber = seasonNumber,
                episodeNumber = episodeNumber,
                episodeTitle = episodeTitle,
                canonicalArtworkUrl = canonicalArtworkUrl,
                progressFraction = progressFraction,
                currentPositionMs = currentPositionMs,
                durationMs = durationMs,
                updatedAt = updatedAt,
                confirmedEmpty = confirmedEmpty
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading Continue Watching snapshot: ${e.message}")
            null
        }
    }

    private fun writeRecordAtomically(targetFile: File, record: RawSnapshotRecord) {
        val parentDir = targetFile.parentFile
        if (parentDir != null && !parentDir.exists()) {
            parentDir.mkdirs()
        }

        val json = JSONObject().apply {
            put("mediaId", record.mediaId)
            put("seriesId", record.seriesId.orEmpty())
            put("isSeries", record.isSeries)
            put("displayHeader", record.displayHeader)
            put("displayTitle", record.displayTitle)
            put("seriesTitle", record.seriesTitle)
            put("seasonNumber", record.seasonNumber)
            put("episodeNumber", record.episodeNumber)
            put("episodeTitle", record.episodeTitle)
            put("canonicalArtworkUrl", record.canonicalArtworkUrl)
            put("progressFraction", record.progressFraction.toDouble())
            put("currentPositionMs", record.currentPositionMs)
            put("durationMs", record.durationMs)
            put("updatedAt", record.updatedAt)
            put("confirmedEmpty", record.confirmedEmpty)
        }.toString()

        val tempFile = File(parentDir, "$SNAPSHOT_FILE_NAME.tmp")
        tempFile.writeText(json, Charsets.UTF_8)
        if (!tempFile.renameTo(targetFile)) {
            if (targetFile.exists()) {
                targetFile.delete()
            }
            if (!tempFile.renameTo(targetFile)) {
                targetFile.writeText(json, Charsets.UTF_8)
                tempFile.delete()
            }
        }
    }

    suspend fun loadSnapshot(
        context: Context,
        profileEmail: String?
    ): LoadedSnapshotResult = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            val file = getSnapshotFile(context, profileEmail)
            val record = readRawRecord(file)
                ?: return@withLock LoadedSnapshotResult(item = null, updatedAt = 0L, confirmedEmpty = false)

            if (record.confirmedEmpty || record.mediaId.isBlank() || record.progressFraction >= 0.95f) {
                return@withLock LoadedSnapshotResult(
                    item = null,
                    updatedAt = record.updatedAt,
                    confirmedEmpty = record.confirmedEmpty
                )
            }

            val displayItem = ContinueWatchingDisplayData(
                mediaId = record.mediaId,
                seriesId = record.seriesId,
                isSeries = record.isSeries,
                displayHeader = record.displayHeader,
                displayTitle = record.displayTitle,
                displayThumbnailUrl = record.canonicalArtworkUrl,
                progressFraction = record.progressFraction,
                currentPositionMs = record.currentPositionMs,
                durationMs = record.durationMs,
                seriesTitle = record.seriesTitle.ifBlank { record.displayHeader },
                seasonNumber = record.seasonNumber,
                episodeNumber = record.episodeNumber,
                episodeTitle = record.episodeTitle.ifBlank { record.displayTitle }
            )

            LoadedSnapshotResult(
                item = displayItem,
                updatedAt = record.updatedAt,
                confirmedEmpty = false
            )
        }
    }

    suspend fun saveSnapshot(
        context: Context,
        profileEmail: String?,
        item: ContinueWatchingDisplayData,
        updatedAt: Long = System.currentTimeMillis()
    ) = withContext(Dispatchers.IO) {
        if (item.mediaId.isBlank()) return@withContext
        ioMutex.withLock {
            try {
                val targetFile = getSnapshotFile(context, profileEmail)
                val previous = readRawRecord(targetFile)

                val incomingUrl = item.displayThumbnailUrl.trim()
                val existingValidArtwork = if (
                    previous != null &&
                    previous.mediaId == item.mediaId &&
                    previous.canonicalArtworkUrl.isNotBlank() &&
                    !OnlineMetadataResolver.isDriveVideoFrameUrl(previous.canonicalArtworkUrl)
                ) {
                    previous.canonicalArtworkUrl
                } else {
                    ""
                }

                val sanitizedArtworkUrl = if (
                    incomingUrl.isNotBlank() &&
                    !OnlineMetadataResolver.isDriveVideoFrameUrl(incomingUrl)
                ) {
                    incomingUrl
                } else {
                    existingValidArtwork
                }

                val normalizedTs = WatchHistoryPayloadV2.normalizeTimestampMs(updatedAt)
                    .takeIf { it > 0L } ?: System.currentTimeMillis()

                val safeHeader = item.displayHeader.ifBlank { item.seriesTitle }
                val safeSeriesTitle = item.seriesTitle.ifBlank { safeHeader }
                val safeEpisodeTitle = item.episodeTitle.ifBlank { item.displayTitle }
                val safeFraction = if (item.durationMs > 0L) {
                    (item.currentPositionMs.toFloat() / item.durationMs.toFloat()).coerceIn(0f, 1f)
                } else {
                    item.progressFraction.coerceIn(0f, 1f)
                }

                val record = RawSnapshotRecord(
                    mediaId = item.mediaId.trim(),
                    seriesId = item.seriesId?.trim()?.ifBlank { null },
                    isSeries = item.isSeries,
                    displayHeader = safeHeader,
                    displayTitle = item.displayTitle,
                    seriesTitle = safeSeriesTitle,
                    seasonNumber = item.seasonNumber,
                    episodeNumber = item.episodeNumber,
                    episodeTitle = safeEpisodeTitle,
                    canonicalArtworkUrl = sanitizedArtworkUrl,
                    progressFraction = safeFraction,
                    currentPositionMs = item.currentPositionMs.coerceAtLeast(0L),
                    durationMs = item.durationMs.coerceAtLeast(0L),
                    updatedAt = normalizedTs,
                    confirmedEmpty = false
                )

                writeRecordAtomically(targetFile, record)
            } catch (e: Exception) {
                Log.e(TAG, "Failed saving Continue Watching snapshot: ${e.message}", e)
            }
        }
    }

    suspend fun markConfirmedEmpty(
        context: Context,
        profileEmail: String?,
        updatedAt: Long = System.currentTimeMillis()
    ) = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            try {
                val targetFile = getSnapshotFile(context, profileEmail)
                val normalizedTs = WatchHistoryPayloadV2.normalizeTimestampMs(updatedAt)
                    .takeIf { it > 0L } ?: System.currentTimeMillis()
                val record = RawSnapshotRecord(
                    mediaId = "",
                    seriesId = null,
                    isSeries = false,
                    displayHeader = "",
                    displayTitle = "",
                    seriesTitle = "",
                    seasonNumber = 0,
                    episodeNumber = 0,
                    episodeTitle = "",
                    canonicalArtworkUrl = "",
                    progressFraction = 0f,
                    currentPositionMs = 0L,
                    durationMs = 0L,
                    updatedAt = normalizedTs,
                    confirmedEmpty = true
                )
                writeRecordAtomically(targetFile, record)
            } catch (e: Exception) {
                Log.e(TAG, "Failed marking Continue Watching snapshot confirmedEmpty: ${e.message}", e)
            }
        }
    }

    suspend fun clearSnapshot(
        context: Context,
        profileEmail: String?
    ) = withContext(Dispatchers.IO) {
        ioMutex.withLock {
            try {
                val targetFile = getSnapshotFile(context, profileEmail)
                val tempFile = File(targetFile.parentFile, "$SNAPSHOT_FILE_NAME.tmp")
                if (tempFile.exists()) tempFile.delete()
                if (targetFile.exists()) targetFile.delete()
            } catch (e: Exception) {
                Log.w(TAG, "Failed clearing Continue Watching snapshot: ${e.message}")
            }
        }
    }

    private data class InProgressCandidate(
        val mediaId: String,
        val seriesId: String?,
        val isSeries: Boolean,
        val title: String,
        val seriesTitle: String?,
        val seasonNumber: Int,
        val episodeNumber: Int,
        val positionMs: Long,
        val durationMs: Long,
        val progressFraction: Float,
        val timestamp: Long,
        val thumbnailUrl: String
    )

    /**
     * Derives the single highest-timestamp in-progress item from typed history entries,
     * excluding anything marked watched, in [watchedIds], in [archivedIds], or >= 95% complete.
     * Enriches title/artwork from [MetadataDiskCache] and strictly rejects Drive video frame URLs.
     */
    suspend fun deriveTopInProgressItem(
        context: Context,
        entries: Map<String, AndroidTypedEntry>,
        watchedIds: Set<String> = emptySet(),
        archivedIds: Set<String> = emptySet(),
        archivedItems: Map<String, HistoryItemEntityV2> = AndroidWatchHistoryManager.snapshotArchivedItems(),
        minPositionMs: Long = 3000L
    ): Pair<ContinueWatchingDisplayData, Long>? = withContext(Dispatchers.IO) {
        if (entries.isEmpty() && archivedItems.isEmpty()) return@withContext null
        runCatching { MetadataDiskCache.ensureLoaded(context.applicationContext) }

        val candidates = mutableListOf<InProgressCandidate>()
        val resumeEpisodeIds = mutableSetOf<String>()
        val activeSeenIds = mutableSetOf<String>()

        entries.keys
            .filter { it.startsWith("series_resume_") && it.endsWith("_episode_id") }
            .forEach { epKey ->
                val sId = epKey.removePrefix("series_resume_").removeSuffix("_episode_id")
                val p = "series_resume_$sId"
                val epId = (entries["${p}_episode_id"]?.value as? String)?.trim().orEmpty()
                if (epId.isNotBlank()) {
                    resumeEpisodeIds.add(epId)
                    val resumeTs = WatchHistoryPayloadV2.normalizeTimestampMs(
                        (entries["${p}_timestamp"]?.value as? Number)?.toLong() ?: 0L
                    )
                    val epTs = WatchHistoryPayloadV2.normalizeTimestampMs(
                        (entries["${epId}_timestamp"]?.value as? Number)?.toLong() ?: 0L
                    )
                    val ts = maxOf(resumeTs, epTs)
                    val pos = (entries["${p}_pos_ms"]?.value as? Number)?.toLong()
                        ?: (entries["${epId}_pos"]?.value as? Number)?.toLong() ?: 0L
                    val dur = (entries["${p}_dur_ms"]?.value as? Number)?.toLong()
                        ?: (entries["${epId}_dur"]?.value as? Number)?.toLong() ?: 0L
                    val frac = (entries["${p}_fraction"]?.value as? Number)?.toFloat()
                        ?: (entries["${epId}_fraction"]?.value as? Number)?.toFloat()
                        ?: if (dur > 0L) (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f

                    val explicitWatched = entries["${epId}_is_watched"]?.value as? Boolean
                    val isInWatchedSets = epId in watchedIds || epId in archivedIds
                    val isWatched = (explicitWatched ?: (isInWatchedSets || frac >= 0.90f)) || isInWatchedSets

                    if (!isWatched && frac < 0.95f && pos > minPositionMs && ts > 0L) {
                        val sNum = (entries["${p}_season_num"]?.value as? Number)?.toInt()
                            ?: (entries["${epId}_season_num"]?.value as? Number)?.toInt() ?: 1
                        val eNum = (entries["${p}_episode_num"]?.value as? Number)?.toInt()
                            ?: (entries["${epId}_episode_num"]?.value as? Number)?.toInt() ?: 1
                        val epTitle = (entries["${p}_episode_title"]?.value as? String)
                            ?: (entries["${epId}_title"]?.value as? String) ?: "Episode $eNum"
                        val sTitle = (entries["${p}_series_title"]?.value as? String)
                            ?: (entries["${epId}_series_title"]?.value as? String)
                        val thumb = (entries["${epId}_thumbnail_url"]?.value as? String)?.takeIf { it.isNotBlank() }
                            ?: (entries["${epId}_thumb"]?.value as? String)?.takeIf { it.isNotBlank() }
                            ?: (entries["${p}_thumbnail_url"]?.value as? String)?.takeIf { it.isNotBlank() }
                            ?: (entries["${p}_thumb"]?.value as? String)?.takeIf { it.isNotBlank() }
                            ?: ""

                        candidates.add(
                            InProgressCandidate(
                                mediaId = epId,
                                seriesId = sId.ifBlank { null },
                                isSeries = true,
                                title = epTitle,
                                seriesTitle = sTitle,
                                seasonNumber = sNum,
                                episodeNumber = eNum,
                                positionMs = pos,
                                durationMs = dur,
                                progressFraction = frac,
                                timestamp = ts,
                                thumbnailUrl = thumb
                            )
                        )
                    }
                }
            }

        entries.keys
            .filter { it.endsWith("_timestamp") && !it.startsWith("series_resume_") && !it.startsWith("global_") }
            .forEach { tsKey ->
                val fileId = tsKey.removeSuffix("_timestamp")
                if (fileId.isNotBlank() && fileId !in resumeEpisodeIds) {
                    activeSeenIds.add(fileId)
                    val ts = WatchHistoryPayloadV2.normalizeTimestampMs(
                        (entries[tsKey]?.value as? Number)?.toLong() ?: 0L
                    )
                    val pos = (entries["${fileId}_pos"]?.value as? Number)?.toLong() ?: 0L
                    val dur = (entries["${fileId}_dur"]?.value as? Number)?.toLong() ?: 0L
                    val frac = (entries["${fileId}_fraction"]?.value as? Number)?.toFloat()
                        ?: if (dur > 0L) (pos.toFloat() / dur.toFloat()).coerceIn(0f, 1f) else 0f

                    val explicitWatched = entries["${fileId}_is_watched"]?.value as? Boolean
                    val isInWatchedSets = fileId in watchedIds || fileId in archivedIds
                    val isWatched = (explicitWatched ?: (isInWatchedSets || frac >= 0.90f)) || isInWatchedSets

                    val isSeries = (entries["${fileId}_is_series"]?.value as? Boolean) ?: false
                    val sId = (entries["${fileId}_series_id"]?.value as? String)?.ifBlank { null }
                    val sNum = (entries["${fileId}_season_num"]?.value as? Number)?.toInt() ?: 0
                    val eNum = (entries["${fileId}_episode_num"]?.value as? Number)?.toInt() ?: 0
                    val title = (entries["${fileId}_title"]?.value as? String) ?: ""
                    val sTitle = (entries["${fileId}_series_title"]?.value as? String)?.ifBlank { null }
                    val thumb = (entries["${fileId}_thumbnail_url"]?.value as? String)?.takeIf { it.isNotBlank() }
                        ?: (entries["${fileId}_thumb"]?.value as? String)?.takeIf { it.isNotBlank() }
                        ?: ""

                    if (!isWatched && frac < 0.95f && pos > minPositionMs && ts > 0L) {
                        candidates.add(
                            InProgressCandidate(
                                mediaId = fileId,
                                seriesId = sId,
                                isSeries = isSeries || sId != null || sNum > 0 || eNum > 0,
                                title = title,
                                seriesTitle = sTitle,
                                seasonNumber = sNum,
                                episodeNumber = eNum,
                                positionMs = pos,
                                durationMs = dur,
                                progressFraction = frac,
                                timestamp = ts,
                                thumbnailUrl = thumb
                            )
                        )
                    }
                }
            }

        // Archive fallback: include any uncompleted in-progress archived items absent from active window
        archivedItems.values.forEach { archived ->
            val aid = archived.id.trim()
            if (aid.isNotBlank() &&
                aid !in resumeEpisodeIds &&
                aid !in activeSeenIds &&
                aid !in watchedIds &&
                archived.isContinueWatchingEligible &&
                archived.positionMs > minPositionMs &&
                archived.timestamp > 0L
            ) {
                candidates.add(
                    InProgressCandidate(
                        mediaId = aid,
                        seriesId = archived.seriesId,
                        isSeries = archived.isSeries,
                        title = archived.title,
                        seriesTitle = archived.seriesTitle,
                        seasonNumber = archived.seasonNumber ?: 0,
                        episodeNumber = archived.episodeNumber ?: 0,
                        positionMs = archived.positionMs,
                        durationMs = archived.durationMs,
                        progressFraction = archived.progressFraction,
                        timestamp = archived.timestamp,
                        thumbnailUrl = archived.thumbnailUrl.orEmpty()
                    )
                )
            }
        }

        val winner = candidates.maxByOrNull { it.timestamp } ?: return@withContext null

        val displayData = if (winner.isSeries) {
            val parsedSe = OnlineMetadataResolver.extractSeasonAndEpisode(winner.title)
            val sNum = when {
                winner.seasonNumber > 0 -> winner.seasonNumber
                parsedSe != null -> parsedSe.first
                else -> 1
            }
            val eNum = when {
                winner.episodeNumber > 0 -> winner.episodeNumber
                parsedSe != null -> parsedSe.second
                else -> 1
            }
            val rawSeriesTitle = winner.seriesTitle?.trim()?.ifBlank { null }
                ?: winner.title
                    .replace(Regex("(?i)\\s*[-•:]?\\s*S0*\\d+[:\\s._-]*E0*\\d+.*$"), "")
                    .trim(' ', '-', '•', ':')
                    .ifBlank { "TV Series" }
            val normSeries = WatchlistManager.normalizeTitle(rawSeriesTitle)
            val epDisk = MetadataDiskCache.get("ep_${normSeries}_s${sNum}e${eNum}")
            val seriesDisk = MetadataDiskCache.get("series_$normSeries")
                ?: MetadataDiskCache.get("series:${rawSeriesTitle.trim().lowercase()}")

            val resolvedSeriesTitle = seriesDisk?.title?.takeIf { it.isNotBlank() } ?: rawSeriesTitle
            val sanitizedEp = OnlineMetadataResolver.sanitizeEpisodeTitle(winner.title, resolvedSeriesTitle, sNum, eNum)
            val cleanEpTitle = epDisk?.title?.takeIf { it.isNotBlank() && !it.startsWith("Episode", ignoreCase = true) }
                ?: sanitizedEp.takeIf { it.isNotBlank() }
                ?: "Episode $eNum"

            val officialArt = listOfNotNull(
                epDisk?.stillUrl,
                epDisk?.posterUrl,
                seriesDisk?.backdropUrl,
                seriesDisk?.posterUrl,
                winner.thumbnailUrl
            ).map { it.trim() }
                .firstOrNull { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                .orEmpty()

            val epCode = "S${sNum.toString().padStart(2, '0')}:E${eNum.toString().padStart(2, '0')}"
            ContinueWatchingDisplayData(
                mediaId = winner.mediaId,
                seriesId = winner.seriesId,
                isSeries = true,
                displayHeader = resolvedSeriesTitle,
                displayTitle = "$epCode • $cleanEpTitle",
                displayThumbnailUrl = officialArt,
                progressFraction = winner.progressFraction.coerceIn(0f, 1f),
                currentPositionMs = winner.positionMs,
                durationMs = winner.durationMs,
                seriesTitle = resolvedSeriesTitle,
                seasonNumber = sNum,
                episodeNumber = eNum,
                episodeTitle = cleanEpTitle
            )
        } else {
            val rawMovieTitle = winner.title.ifBlank { "Movie" }
            val cleanTitle = DriveRepository.cleanVideoTitle(rawMovieTitle)
            val cachedMeta = OnlineMetadataResolver.getCachedMovieMetadata(context, winner.mediaId)
                ?: OnlineMetadataResolver.getCachedMovieMetadata(context, cleanTitle)
            val officialTitle = cachedMeta?.officialTitle?.takeIf { it.isNotBlank() } ?: cleanTitle
            val officialArt = listOfNotNull(
                cachedMeta?.posterUrl,
                winner.thumbnailUrl
            ).map { it.trim() }
                .firstOrNull { it.isNotBlank() && !OnlineMetadataResolver.isDriveVideoFrameUrl(it) }
                .orEmpty()

            ContinueWatchingDisplayData(
                mediaId = winner.mediaId,
                seriesId = null,
                isSeries = false,
                displayHeader = officialTitle,
                displayTitle = "Movie • Cinema",
                displayThumbnailUrl = officialArt,
                progressFraction = winner.progressFraction.coerceIn(0f, 1f),
                currentPositionMs = winner.positionMs,
                durationMs = winner.durationMs,
                seriesTitle = officialTitle,
                seasonNumber = 0,
                episodeNumber = 0,
                episodeTitle = officialTitle
            )
        }

        displayData to winner.timestamp
    }
}
