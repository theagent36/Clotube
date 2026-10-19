package com.clotube.app.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.clotube.app.auth.GoogleAuthRepository
import com.clotube.app.data.PlaybackHistoryManager
import com.clotube.app.data.SeriesResumeState
import com.clotube.app.data.SeriesRepository
import com.clotube.app.data.WatchProgress
import com.clotube.app.model.Episode
import com.clotube.app.model.Season
import com.clotube.app.model.TvSeries
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch

@androidx.annotation.Keep
data class SeriesDetailUiState(
    val series: TvSeries? = null,
    val selectedSeasonIndex: Int = 0,
    val selectedEpisode: Episode? = null,
    val seriesResumeState: SeriesResumeState? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null
) {
    val selectedSeason: Season?
        get() = series?.seasons?.getOrNull(selectedSeasonIndex)

    val currentPlayingEpisode: Episode?
        get() = selectedEpisode
}

class SeriesDetailViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "SeriesDetailVM"
    }

    private val authRepository = GoogleAuthRepository(application)
    private val seriesRepository = SeriesRepository(authRepository)
    val playbackHistoryManager = PlaybackHistoryManager(application)
    val vlcPlayerManager = com.clotube.app.media.VlcPlayerManager(
        context = application,
        tokenProvider = { getValidAccessToken() }
    )
    val mediaPlayer: org.videolan.libvlc.MediaPlayer get() = vlcPlayerManager.mediaPlayer

    private val _uiState = MutableStateFlow(SeriesDetailUiState())
    val uiState: StateFlow<SeriesDetailUiState> = _uiState.asStateFlow()

    private var currentSeriesId: String? = null
    private var progressCache: Map<String, WatchProgress> = emptyMap()

    /**
     * Exposed as a StateFlow so that [SeriesDetailScreen] can read it via `collectAsState()`
     * without an additional argument. The value is kept in sync by the watcher below.
     */
    private val _seriesResumeState = MutableStateFlow<SeriesResumeState?>(null)
    val seriesResumeState: StateFlow<SeriesResumeState?> = _seriesResumeState.asStateFlow()

    init {
        viewModelScope.launch {
            playbackHistoryManager.getAllProgress().collect { progressMap ->
                progressCache = progressMap
                val currentSeries = _uiState.value.series ?: return@collect

                val updatedSeasons = currentSeries.seasons.map { season ->
                    season.copy(
                        episodes = season.episodes.map { ep ->
                            val prog = progressMap[ep.id]
                            if (prog != null) ep.copy(watchProgressFraction = prog.progressFraction)
                            else ep
                        }
                    )
                }
                val updatedSeries = currentSeries.copy(seasons = updatedSeasons)

                // Re-resolve selectedEpisode only when user hasn't manually selected one.
                // If a series resume state exists, honour it; otherwise fall back to heuristic.
                val activeEp = _uiState.value.selectedEpisode
                val resume = _uiState.value.seriesResumeState

                val updatedActiveEp = if (activeEp != null) {
                    // Refresh watchProgressFraction on the already-selected episode.
                    val p = progressMap[activeEp.id]
                    if (p != null) activeEp.copy(watchProgressFraction = p.progressFraction) else activeEp
                } else {
                    resolveTargetEpisode(updatedSeries, resume, progressMap)
                }

                _uiState.update {
                    it.copy(
                        series = updatedSeries,
                        selectedEpisode = updatedActiveEp
                    )
                }
            }
        }
    }

    fun getSeriesById(seriesId: String): TvSeries? {
        val inMemory = _uiState.value.series
        return if (inMemory?.id == seriesId) inMemory else null
    }

    fun initialize(
        seriesId: String,
        initialSeries: TvSeries? = null,
        token: String? = null
    ) {
        if (currentSeriesId == seriesId && _uiState.value.series != null && _uiState.value.series!!.seasons.isNotEmpty()) return
        currentSeriesId = seriesId

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = (_uiState.value.series == null)) }

            // 1. Await the saved series resume state before defaulting to S01E01
            val resumeState = withContext(Dispatchers.IO) {
                playbackHistoryManager.getSeriesResumeStateOnce(seriesId)
            }
            _seriesResumeState.value = resumeState
            _uiState.update { it.copy(seriesResumeState = resumeState) }

            // 2. Resolve cached series: check initialSeries first, then fallback to disk cache on Dispatchers.IO
            val effectiveInitialSeries = if (initialSeries != null && initialSeries.seasons.isNotEmpty()) {
                initialSeries
            } else {
                withContext(Dispatchers.IO) {
                    loadCatalogFromDisk(getApplication()).find { it.id == seriesId }
                } ?: initialSeries
            }

            if (effectiveInitialSeries != null && effectiveInitialSeries.seasons.isNotEmpty()) {
                val initialEp = resolveTargetEpisode(effectiveInitialSeries, resumeState, progressCache)
                val seasonIdx = if (initialEp != null) {
                    effectiveInitialSeries.seasons.indexOfFirst { s -> s.episodes.any { it.id == initialEp.id } }
                        .coerceAtLeast(0)
                } else {
                    0
                }

                _uiState.update {
                    it.copy(
                        series = effectiveInitialSeries,
                        selectedSeasonIndex = seasonIdx,
                        selectedEpisode = initialEp
                    )
                }
            }

            // 3. Fetch full fresh show details from Google Drive (if online)
            try {
                val freshSeries = seriesRepository.getShowDetails(seriesId, effectiveInitialSeries?.title)
                if (freshSeries.seasons.isNotEmpty()) {
                    val updatedSeasons = freshSeries.seasons.map { season ->
                        season.copy(
                            episodes = season.episodes.map { ep ->
                                val prog = progressCache[ep.id]
                                if (prog != null) ep.copy(watchProgressFraction = prog.progressFraction)
                                else ep
                            }
                        )
                    }
                    val updatedSeries = freshSeries.copy(seasons = updatedSeasons)

                    // Persist the show with its populated seasons and episodes to disk!
                    withContext(Dispatchers.IO) {
                        saveShowToDisk(getApplication(), updatedSeries)
                    }

                    val currentResume = _uiState.value.seriesResumeState ?: resumeState
                    val targetEp = resolveTargetEpisode(updatedSeries, currentResume, progressCache)
                        ?: _uiState.value.selectedEpisode?.let { cur ->
                            updatedSeries.seasons.flatMap { it.episodes }.find { it.id == cur.id }
                        }

                    val seasonIdx = if (targetEp != null) {
                        updatedSeries.seasons.indexOfFirst { s -> s.episodes.any { it.id == targetEp.id } }
                            .takeIf { it >= 0 }
                            ?: _uiState.value.selectedSeasonIndex.coerceIn(0, updatedSeries.seasons.lastIndex)
                    } else {
                        _uiState.value.selectedSeasonIndex.coerceIn(0, updatedSeries.seasons.lastIndex)
                    }

                    _uiState.update {
                        it.copy(
                            series = updatedSeries,
                            selectedSeasonIndex = seasonIdx,
                            selectedEpisode = targetEp,
                            isLoading = false
                        )
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed loading show details from Drive (offline / airplane mode)", e)
                // If we already have a cached series loaded, do NOT show error screen!
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        errorMessage = if (it.series == null) e.localizedMessage else null
                    )
                }
            }
        }

        // Live observation stream for real-time updates while on screen
        viewModelScope.launch {
            playbackHistoryManager.getSeriesResumeState(seriesId).collect { resumeState ->
                _seriesResumeState.value = resumeState
                _uiState.update { it.copy(seriesResumeState = resumeState) }
            }
        }
    }

    fun selectSeason(index: Int) {
        val totalSeasons = _uiState.value.series?.seasons?.size ?: 0
        if (index in 0 until totalSeasons) {
            _uiState.update { it.copy(selectedSeasonIndex = index) }
        }
    }

    fun selectEpisode(episode: Episode) {
        _uiState.update {
            it.copy(selectedEpisode = episode)
        }
    }

    fun playEpisode(episode: Episode) {
        selectEpisode(episode)
    }

    suspend fun getLastWatchedEpisode(series: com.clotube.app.model.Series): com.clotube.app.model.Episode? {
        return playbackHistoryManager.getLastWatchedEpisode(series)
    }

    private val tokenRepository = com.clotube.app.data.TokenRepository(application)

    suspend fun getFreshAccessToken(): String = withContext(Dispatchers.IO) {
        val token = tokenRepository.getValidAccessToken()
        if (token.isNotBlank()) return@withContext token
        val repoToken = authRepository.getAccessToken().getOrNull()
            ?: authRepository.refreshAccessToken().getOrNull()
        if (!repoToken.isNullOrBlank()) return@withContext repoToken
        ""
    }

    suspend fun getValidAccessToken(): String = getFreshAccessToken()

    override fun onCleared() {
        super.onCleared()
        vlcPlayerManager.release()
    }

    fun saveCurrentEpisodeProgress() {}
    fun dismissAutoplayNotice() {}
    fun togglePlayPause() {}
    fun seekTo(fraction: Float) {}
    fun rewind10Seconds() {}
    fun forward10Seconds() {}
    fun playNextEpisode() {}
    fun playPreviousEpisode() {}
    fun toggleControlsVisibility() {}

    /**
     * Determines the target episode to surface in the Watch Bar and to pre-select in the
     * season tab list.
     *
     * Resolution priority:
     * 1. If a [SeriesResumeState] exists, locate the episode it references by ID, then by
     *    episodeNumber as a fallback, within the correct season.
     * 2. If no resume state exists, fall back to [resolveInitialEpisode] which scans all
     *    per-episode [WatchProgress] records (in-progress → just-finished next → S01E01).
     */
    private fun resolveTargetEpisode(
        series: TvSeries,
        resumeState: SeriesResumeState?,
        progressMap: Map<String, WatchProgress>
    ): Episode? {
        if (resumeState != null) {
            val matchedSeason = series.seasons.find { it.seasonNumber == resumeState.seasonNumber }
                ?: series.seasons.firstOrNull()
            val matchedEp = matchedSeason?.episodes?.find { ep ->
                ep.id == resumeState.lastEpisodeId || ep.episodeNumber == resumeState.episodeNumber
            } ?: matchedSeason?.episodes?.firstOrNull()

            if (matchedEp != null) {
                // Refresh watchProgressFraction from live progress cache if available.
                val prog = progressMap[matchedEp.id]
                return if (prog != null) matchedEp.copy(watchProgressFraction = prog.progressFraction)
                else matchedEp
            }
        }
        return resolveInitialEpisode(series, progressMap)
    }

    private fun resolveInitialEpisode(series: TvSeries, progressMap: Map<String, WatchProgress>): Episode? {
        val allEpisodes = series.seasons.flatMap { it.episodes }
        if (allEpisodes.isEmpty()) return null

        // 1. Look for an episode in progress (between 2% and 95%)
        val inProgressEpisodes = allEpisodes.filter { ep ->
            val p = progressMap[ep.id]?.progressFraction ?: ep.watchProgressFraction
            p in 0.02f..0.95f
        }
        if (inProgressEpisodes.isNotEmpty()) {
            return inProgressEpisodes.maxByOrNull { ep ->
                progressMap[ep.id]?.lastWatchedTimestamp ?: 0L
            } ?: inProgressEpisodes.first()
        }

        // 2. Check if an episode was recently finished (> 95%) — pick the next episode in sequence.
        val finishedEpisodes = allEpisodes.filter { ep ->
            val p = progressMap[ep.id]?.progressFraction ?: ep.watchProgressFraction
            p > 0.95f
        }
        if (finishedEpisodes.isNotEmpty()) {
            val lastFinished = finishedEpisodes.maxByOrNull { ep ->
                progressMap[ep.id]?.lastWatchedTimestamp ?: 0L
            } ?: finishedEpisodes.last()

            val idx = allEpisodes.indexOfFirst { it.id == lastFinished.id }
            if (idx in 0 until allEpisodes.lastIndex) {
                return allEpisodes[idx + 1]
            }
        }

        // 3. Default to Season 1, Episode 1.
        return series.seasons.firstOrNull()?.episodes?.firstOrNull() ?: allEpisodes.first()
    }
}
