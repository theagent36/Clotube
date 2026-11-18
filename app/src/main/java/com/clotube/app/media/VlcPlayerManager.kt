package com.clotube.app.media

import android.content.Context
import android.net.Uri
import android.util.Log
import com.clotube.app.data.LocalStreamProxy
import com.clotube.app.data.PlaybackTrackPreferences
import com.clotube.app.ui.components.safeCall
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File

class VlcPlayerManager(
    private val context: Context,
    tokenProvider: suspend () -> String
) {
    companion object {
        private const val TAG = "VlcPlayerManager"
    }

    init {
        LocalStreamProxy.initTokenProvider(tokenProvider)
        LocalStreamProxy.start()
    }

    private val playerLock = Any()

    @Volatile
    private var _libVLC: LibVLC? = null

    @Volatile
    private var _mediaPlayer: MediaPlayer? = null

    private fun getOrCreateLibVLC(): LibVLC {
        val existing = _libVLC
        if (existing != null && !existing.isReleased) {
            return existing
        }
        return synchronized(playerLock) {
            val current = _libVLC
            if (current != null && !current.isReleased) {
                current
            } else {
                val options = arrayListOf(
                    "--aout=audiotrack",
                    "--audio-time-stretch",
                    "--network-caching=1500",
                    "--file-caching=1500",
                    "--live-caching=1500",
                    "--no-drop-late-frames",
                    "--no-skip-frames",
                    "--avcodec-fast",
                    "--avcodec-skiploopfilter=4",
                    "--no-sub-autodetect-file"
                )
                LibVLC(context.applicationContext, options).also { _libVLC = it }
            }
        }
    }

    private fun createDefaultEventListener(): MediaPlayer.EventListener = MediaPlayer.EventListener { event ->
        when (event.type) {
            MediaPlayer.Event.Playing -> {
                isPlaybackEnded = false
            }
            MediaPlayer.Event.TimeChanged -> {
                if (event.timeChanged > 0L && pendingTrackRestore) {
                    restoreTracksIfNeeded()
                }
            }
            MediaPlayer.Event.EndReached -> {
                isPlaybackEnded = true
            }
        }
    }

    /**
     * Returns the live [MediaPlayer] instance if not released; otherwise recreates a fresh
     * [MediaPlayer] bound to a live [LibVLC] engine so playback never crashes with
     * `IllegalStateException: can't get VLCObject instance`.
     */
    fun getOrCreateMediaPlayer(): MediaPlayer {
        val existing = _mediaPlayer
        if (existing != null && !existing.isReleased) {
            return existing
        }
        return synchronized(playerLock) {
            val current = _mediaPlayer
            if (current != null && !current.isReleased) {
                current
            } else {
                val vlc = getOrCreateLibVLC()
                MediaPlayer(vlc).apply {
                    try {
                        setEventListener(createDefaultEventListener())
                    } catch (_: Throwable) {}
                }.also { _mediaPlayer = it }
            }
        }
    }

    /**
     * Returns the currently active [MediaPlayer] only if already created and not released.
     * Avoids allocating a new LibVLC instance during teardown/disposal paths.
     */
    fun activeMediaPlayerOrNull(): MediaPlayer? {
        return try {
            _mediaPlayer?.takeIf { !it.isReleased }
        } catch (_: Throwable) {
            null
        }
    }

    val mediaPlayer: MediaPlayer
        get() = getOrCreateMediaPlayer()

    private var activeMedia: Media? = null
    private var activeFileId: String? = null
    private var activeLocalFile: File? = null
    var isPlaybackEnded: Boolean = false
        private set
    private var pendingSeekJob: Job? = null

    @Volatile
    var pendingStartPositionMs: Long = 0L
        private set

    var userSelectedAudioTrackId: Int? = null
    var userSelectedSpuTrackId: Int? = null
    var userSelectedPlaybackSpeed: Float = 1.0f
    var pendingTrackRestore: Boolean = false

    fun restoreTracksIfNeeded() {
        if (!pendingTrackRestore) return
        val player = activeMediaPlayerOrNull() ?: return
        val availableAudio = player.safeCall(null) { audioTracks }
        if (availableAudio.isNullOrEmpty()) return

        userSelectedAudioTrackId?.let { targetAudioId ->
            if (availableAudio.any { it.id == targetAudioId } && player.safeCall(-1) { audioTrack } != targetAudioId) {
                player.safeCall(Unit) { audioTrack = targetAudioId }
            }
        }

        userSelectedSpuTrackId?.let { targetSpuId ->
            val availableSpu = player.safeCall(null) { spuTracks }
            if (targetSpuId == -1 || availableSpu?.any { it.id == targetSpuId } == true) {
                if (player.safeCall(-1) { spuTrack } != targetSpuId) {
                    player.safeCall(Unit) { spuTrack = targetSpuId }
                }
            }
        }

        if (player.safeCall(1.0f) { rate } != userSelectedPlaybackSpeed) {
            player.safeCall(Unit) { rate = userSelectedPlaybackSpeed }
        }

        pendingTrackRestore = false
    }

    fun attachLayout(layout: VLCVideoLayout) {
        val player = getOrCreateMediaPlayer()
        player.safeCall(Unit) {
            try {
                detachViews()
            } catch (_: Throwable) {}
            // Parameter 4 (textureView) = true: Enables TextureView so video scales dynamically in Compose
            attachViews(layout, null, true, true)
            try {
                videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
            } catch (_: Throwable) {}
            try {
                aspectRatio = "16:9"
            } catch (_: Throwable) {}
            Log.d(TAG, "Attached VLC layout with TextureView and SURFACE_BEST_FIT")
        }
    }

    fun detachLayout() {
        activeMediaPlayerOrNull().safeCall(Unit) {
            detachViews()
        }
    }

    /**
     * Stops playback and detaches views cleanly WITHOUT releasing or poisoning the MediaPlayer
     * instance so the next movie/episode can reuse it immediately.
     */
    fun stopAndDetachSafely() {
        pendingSeekJob?.cancel()
        pendingSeekJob = null
        pendingStartPositionMs = 0L
        val player = activeMediaPlayerOrNull()
        player.safeCall(Unit) {
            if (isPlaying) {
                stop()
            }
        }
        player.safeCall(Unit) {
            detachViews()
        }
        try {
            activeMedia?.release()
        } catch (_: Throwable) {}
        activeMedia = null
    }

    @Volatile
    var isAppForegrounded: Boolean = true

    fun onAppResume() {
        isAppForegrounded = true
    }

    fun onAppPause() {
        isAppForegrounded = false
        activeMediaPlayerOrNull().safeCall(Unit) {
            pause()
        }
    }

    fun softReload(positionMs: Long = activeMediaPlayerOrNull().safeCall(0L) { time }) {
        val currentFileId = activeFileId ?: return
        val resumeMs = positionMs.coerceAtLeast(0L)
        Log.d(TAG, "softReload invoked for $currentFileId at ${resumeMs}ms")
        val player = activeMediaPlayerOrNull()
        val curAudio = player.safeCall(-1) { audioTrack }
        if (userSelectedAudioTrackId == null && curAudio != -1) {
            userSelectedAudioTrackId = curAudio
        }
        val curSpu = player.safeCall(-1) { spuTrack }
        if (userSelectedSpuTrackId == null && curSpu != -1) {
            userSelectedSpuTrackId = curSpu
        }
        pendingTrackRestore = true
        CoroutineScope(Dispatchers.IO).launch {
            val downloadRepo = com.clotube.app.data.DownloadRepository(context)
            val localFile = downloadRepo.resolveVerifiedOfflineFile(currentFileId)
            withContext(Dispatchers.Main) {
                play(currentFileId, localFile, resumeMs)
            }
        }
    }

    fun play(
        fileId: String,
        localFile: File? = null,
        startPositionMs: Long = 0L
    ) {
        pendingSeekJob?.cancel()
        if (activeFileId != fileId) {
            userSelectedAudioTrackId = null
            userSelectedSpuTrackId = null
            pendingTrackRestore = false
        }
        activeFileId = fileId
        activeLocalFile = localFile
        isPlaybackEnded = false

        val vlc = getOrCreateLibVLC()
        val player = getOrCreateMediaPlayer()

        player.safeCall(Unit) { stop() }
        try {
            activeMedia?.release()
        } catch (_: Throwable) {}
        activeMedia = null

        val media = try {
            if (localFile != null && localFile.exists()) {
                Media(vlc, Uri.fromFile(localFile)).apply {
                    addOption(":file-caching=1500")
                    addOption(":clock-jitter=0")
                    addOption(":clock-synchro=0")
                }
            } else {
                val proxyUrl = "http://127.0.0.1:${LocalStreamProxy.PORT}/stream?fileId=$fileId"
                Media(vlc, Uri.parse(proxyUrl)).apply {
                    addOption(":network-caching=2000")
                    addOption(":file-caching=2000")
                    addOption(":http-reconnect")
                    addOption(":clock-jitter=0")
                    addOption(":clock-synchro=0")
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create LibVLC Media for $fileId: ${t.message}", t)
            return
        }

        try {
            media.apply {
                setHWDecoderEnabled(true, true)
                val startSecond = (startPositionMs / 1000L).coerceAtLeast(0L)
                applyClotubeMediaOptions(startSecond)
            }
            player.safeCall(Unit) { this.media = media }
        } finally {
            try {
                media.release()
            } catch (_: Throwable) {}
        }

        // RACE CONDITION FIX: Verify app is still active in foreground before starting
        if (isAppForegrounded) {
            player.safeCall(Unit) { play() }
            Log.d(TAG, "Fast-started LibVLC for $fileId with startPositionMs=${startPositionMs}ms")
        } else {
            // App was minimized while URL was resolving; stay ready but paused
            player.safeCall(Unit) { pause() }
            Log.d(TAG, "Prepared LibVLC for $fileId in paused state because app is backgrounded")
        }

        // Native container seek is handled via :start-time=$startSecond in applyClotubeMediaOptions.
        // Secondary seeking during playback start causes LibVLC buffer flush and pause.
        pendingStartPositionMs = 0L
    }

    fun seekTo(positionMs: Long) {
        pendingSeekJob?.cancel()
        pendingStartPositionMs = 0L
        val player = activeMediaPlayerOrNull() ?: return
        val currentlyPlaying = player.safeCall(false) { isPlaying }
        val currentTime = player.safeCall(-1L) { time }
        val totalLength = player.safeCall(0L) { length }
        val isEndedOrEof = isPlaybackEnded ||
            (!currentlyPlaying && (currentTime == -1L || (totalLength > 0L && currentTime >= totalLength - 1500L)))
        val currentFileId = activeFileId
        if (isEndedOrEof && currentFileId != null) {
            isPlaybackEnded = false
            play(currentFileId, activeLocalFile, positionMs)
            return
        }
        if (totalLength > 0L) {
            val maxSafeBoundaryMs = (totalLength - 3000L).coerceAtLeast(0L)
            player.safeCall(Unit) { time = positionMs.coerceIn(0L, maxSafeBoundaryMs) }
            Log.d(TAG, "LibVLC seekTo: ${positionMs}ms (clamped to max: ${maxSafeBoundaryMs}ms)")
        } else {
            player.safeCall(Unit) { time = positionMs }
        }
    }

    fun setSpeed(speed: Float) {
        userSelectedPlaybackSpeed = speed
        activeMediaPlayerOrNull().safeCall(Unit) {
            rate = speed
        }
    }

    fun setSubtitleTrack(trackId: Int) {
        userSelectedSpuTrackId = trackId
        val player = activeMediaPlayerOrNull()
        player.safeCall(Unit) { spuTrack = trackId }
        CoroutineScope(NonCancellable + Dispatchers.IO).launch {
            try {
                if (trackId == -1) {
                    PlaybackTrackPreferences.saveSubtitleSelection(context, -1, "Off", -1)
                } else {
                    val tracks = player.safeCall(null) { spuTracks }?.filter { it.id != -1 }
                    val match = tracks?.firstOrNull { it.id == trackId }
                    val activeIdx = tracks?.indexOfFirst { it.id == trackId } ?: 0
                    PlaybackTrackPreferences.saveSubtitleSelection(context, trackId, match?.name, activeIdx)
                }
            } catch (_: Throwable) {}
        }
    }

    fun setAudioTrack(trackId: Int) {
        userSelectedAudioTrackId = trackId
        val player = activeMediaPlayerOrNull()
        player.safeCall(Unit) { audioTrack = trackId }
        CoroutineScope(NonCancellable + Dispatchers.IO).launch {
            try {
                val tracks = player.safeCall(null) { audioTracks }?.filter { it.id != -1 }
                val match = tracks?.firstOrNull { it.id == trackId }
                PlaybackTrackPreferences.saveAudioSelection(context, trackId, match?.name)
            } catch (_: Throwable) {}
        }
    }

    fun release() {
        synchronized(playerLock) {
            try {
                pendingSeekJob?.cancel()
                pendingSeekJob = null
                pendingStartPositionMs = 0L
                val player = _mediaPlayer
                _mediaPlayer = null
                player.safeCall(Unit) { setEventListener(null) }
                player.safeCall(Unit) { stop() }
                player.safeCall(Unit) { detachViews() }
                try {
                    activeMedia?.release()
                } catch (_: Throwable) {}
                activeMedia = null
                try {
                    if (player != null && !player.isReleased) {
                        player.release()
                    }
                } catch (_: Throwable) {}
                val vlc = _libVLC
                _libVLC = null
                try {
                    if (vlc != null && !vlc.isReleased) {
                        vlc.release()
                    }
                } catch (_: Throwable) {}
            } catch (e: Throwable) {
                Log.e(TAG, "Error releasing VLC: ${e.message}")
            }
        }
    }
}
