package com.clotube.app.media

import android.content.Context
import android.net.Uri
import android.util.Log
import com.clotube.app.data.LocalStreamProxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    private val libVLC: LibVLC by lazy {
        val options = arrayListOf(
            "--network-caching=5000",
            "--file-caching=3000",
            "--live-caching=3000",
            "--clock-jitter=0",
            "--drop-late-frames",
            "--skip-frames",
            "--avcodec-fast",
            "--avcodec-skiploopfilter=4",
            "--no-sub-autodetect-file",
            "--http-reconnect"
        )
        LibVLC(context.applicationContext, options)
    }

    val mediaPlayer: MediaPlayer by lazy {
        MediaPlayer(libVLC)
    }

    private var activeMedia: Media? = null
    private var activeFileId: String? = null
    private var activeLocalFile: File? = null
    var isPlaybackEnded: Boolean = false
        private set
    private var pendingSeekJob: kotlinx.coroutines.Job? = null
    @Volatile
    var pendingStartPositionMs: Long = 0L
        private set

    init {
        mediaPlayer.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    isPlaybackEnded = false
                }
                MediaPlayer.Event.EndReached -> {
                    isPlaybackEnded = true
                }
            }
        }
    }

    fun attachLayout(layout: VLCVideoLayout) {
        try {
            mediaPlayer.detachViews()
            // Parameter 4 (textureView) = true: Enables TextureView so video scales dynamically in Compose
            mediaPlayer.attachViews(layout, null, true, true)
            mediaPlayer.videoScale = MediaPlayer.ScaleType.SURFACE_BEST_FIT
            mediaPlayer.aspectRatio = "16:9"
            Log.d(TAG, "Attached VLC layout with TextureView and SURFACE_BEST_FIT")
        } catch (e: Exception) {
            Log.e(TAG, "Error attaching VLC layout: ${e.message}")
        }
    }

    fun detachLayout() {
        try {
            mediaPlayer.detachViews()
        } catch (e: Exception) {
            Log.e(TAG, "Error detaching VLC layout: ${e.message}")
        }
    }

    @Volatile
    var isAppForegrounded: Boolean = true

    fun onAppResume() {
        isAppForegrounded = true
    }

    fun onAppPause() {
        isAppForegrounded = false
        try {
            mediaPlayer.pause()
        } catch (_: Exception) {}
    }

    fun softReload(positionMs: Long = mediaPlayer.time) {
        val currentFileId = activeFileId ?: return
        val resumeMs = positionMs.coerceAtLeast(0L)
        Log.d(TAG, "softReload invoked for $currentFileId at ${resumeMs}ms")
        play(currentFileId, activeLocalFile, resumeMs)
    }

    fun play(
        fileId: String,
        localFile: File? = null,
        startPositionMs: Long = 0L
    ) {
        pendingSeekJob?.cancel()
        activeFileId = fileId
        activeLocalFile = localFile
        isPlaybackEnded = false
        mediaPlayer.stop()
        activeMedia?.release()

        val media = if (localFile != null && localFile.exists()) {
            Media(libVLC, Uri.fromFile(localFile))
        } else {
            val proxyUrl = "http://127.0.0.1:${LocalStreamProxy.PORT}/stream?fileId=$fileId"
            Media(libVLC, Uri.parse(proxyUrl))
        }

        media.apply {
            setHWDecoderEnabled(true, false)
            val startSecond = (startPositionMs / 1000L).coerceAtLeast(0L)
            applyClotubeMediaOptions(startSecond)
        }

        activeMedia = media
        mediaPlayer.media = media
        media.release()

        // RACE CONDITION FIX: Verify app is still active in foreground before starting
        if (isAppForegrounded) {
            mediaPlayer.play()
            Log.d(TAG, "Fast-started LibVLC for $fileId with startPositionMs=${startPositionMs}ms")
        } else {
            // App was minimized while URL was resolving; stay ready but paused
            mediaPlayer.pause()
            Log.d(TAG, "Prepared LibVLC for $fileId in paused state because app is backgrounded")
        }

        // CRITICAL FIX: Ensure seek is preserved and applied during buffering on cold restart
        if (startPositionMs > 1000L) {
            pendingStartPositionMs = startPositionMs
            pendingSeekJob = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                val startWait = System.currentTimeMillis()
                while (pendingStartPositionMs > 1000L && System.currentTimeMillis() - startWait < 15000L) {
                    kotlinx.coroutines.delay(50L)
                    val len = mediaPlayer.length
                    val isPlaying = mediaPlayer.isPlaying
                    val isSeekable = mediaPlayer.isSeekable
                    if (len > 0L || (isPlaying && isSeekable)) {
                        val target = pendingStartPositionMs
                        pendingStartPositionMs = 0L
                        val maxSafeBoundaryMs = if (len > 0L) (len - 3000L).coerceAtLeast(0L) else target
                        val clamped = if (maxSafeBoundaryMs > 0L) target.coerceIn(0L, maxSafeBoundaryMs) else target
                        mediaPlayer.time = clamped
                        Log.d(TAG, "Preserved seek applied during buffering: ${clamped}ms")
                        break
                    }
                }
            }
        } else {
            pendingStartPositionMs = 0L
        }
    }

    fun seekTo(positionMs: Long) {
        pendingSeekJob?.cancel()
        pendingStartPositionMs = 0L
        val isEndedOrEof = isPlaybackEnded || (!mediaPlayer.isPlaying && (mediaPlayer.time == -1L || (mediaPlayer.length > 0L && mediaPlayer.time >= mediaPlayer.length - 1500L)))
        val currentFileId = activeFileId
        if (isEndedOrEof && currentFileId != null) {
            isPlaybackEnded = false
            play(currentFileId, activeLocalFile, positionMs)
            return
        }
        val totalLength = mediaPlayer.length
        if (totalLength > 0L) {
            val maxSafeBoundaryMs = (totalLength - 3000L).coerceAtLeast(0L)
            mediaPlayer.time = positionMs.coerceIn(0L, maxSafeBoundaryMs)
            Log.d(TAG, "LibVLC seekTo: ${positionMs}ms (clamped to max: ${maxSafeBoundaryMs}ms)")
        } else {
            mediaPlayer.time = positionMs
        }
    }

    fun setSpeed(speed: Float) {
        mediaPlayer.rate = speed
    }

    fun setSubtitleTrack(trackId: Int) {
        mediaPlayer.spuTrack = trackId
    }

    fun setAudioTrack(trackId: Int) {
        mediaPlayer.audioTrack = trackId
    }

    fun release() {
        try {
            pendingSeekJob?.cancel()
            pendingStartPositionMs = 0L
            mediaPlayer.stop()
            mediaPlayer.detachViews()
            activeMedia?.release()
            mediaPlayer.release()
            libVLC.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing VLC: ${e.message}")
        }
    }
}
