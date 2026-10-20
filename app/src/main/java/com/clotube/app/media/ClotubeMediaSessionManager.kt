package com.clotube.app.media

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.Keep
import androidx.core.content.IntentCompat

@Keep
class ClotubeMediaSessionManager(
    private val context: Context,
    private val actionPlayPauseToggle: () -> Unit,
    private val actionPlay: () -> Unit,
    private val actionPause: () -> Unit,
    private val actionNext: (() -> Unit)? = null,
    private val actionPrevious: (() -> Unit)? = null,
    private val actionFastForward: (() -> Unit)? = null,
    private val actionRewind: (() -> Unit)? = null
) {
    private var mediaSession: MediaSessionCompat? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun initialize(): MediaSessionCompat {
        if (mediaSession != null) return mediaSession!!

        val session = MediaSessionCompat(context, "ClotubeMediaSession").apply {
            @Suppress("DEPRECATION")
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )

            // Explicitly bind callback to the Main Looper to guarantee thread safety with LibVLC and Compose
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    Log.d("ClotubeMediaSession", "AVRCP onPlay received")
                    actionPlay.invoke()
                }

                override fun onPause() {
                    Log.d("ClotubeMediaSession", "AVRCP onPause received")
                    actionPause.invoke()
                }

                override fun onSkipToNext() {
                    Log.d("ClotubeMediaSession", "AVRCP onSkipToNext received")
                    actionNext?.invoke()
                }

                override fun onSkipToPrevious() {
                    Log.d("ClotubeMediaSession", "AVRCP onSkipToPrevious received")
                    actionPrevious?.invoke()
                }

                override fun onFastForward() {
                    Log.d("ClotubeMediaSession", "AVRCP onFastForward received")
                    actionFastForward?.invoke()
                }

                override fun onRewind() {
                    Log.d("ClotubeMediaSession", "AVRCP onRewind received")
                    actionRewind?.invoke()
                }

                override fun onMediaButtonEvent(mediaButtonEvent: Intent?): Boolean {
                    if (mediaButtonEvent == null) return false

                    val keyEvent = IntentCompat.getParcelableExtra(
                        mediaButtonEvent,
                        Intent.EXTRA_KEY_EVENT,
                        KeyEvent::class.java
                    ) ?: return super.onMediaButtonEvent(mediaButtonEvent)

                    // Only handle on ACTION_DOWN; consume ACTION_UP to prevent duplicate framework triggers
                    if (keyEvent.action == KeyEvent.ACTION_UP) {
                        return true
                    }

                    if (keyEvent.action == KeyEvent.ACTION_DOWN) {
                        Log.d("ClotubeMediaSession", "Media button key code: ${keyEvent.keyCode}")
                        when (keyEvent.keyCode) {
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                            KeyEvent.KEYCODE_HEADSETHOOK -> {
                                actionPlayPauseToggle.invoke()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                                actionPlay.invoke()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                actionPause.invoke()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_NEXT -> {
                                actionNext?.invoke()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                                actionPrevious?.invoke()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                                actionFastForward?.invoke()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                                actionRewind?.invoke()
                                return true
                            }
                        }
                    }
                    return super.onMediaButtonEvent(mediaButtonEvent)
                }
            }, mainHandler)

            isActive = true
        }
        mediaSession = session
        return session
    }

    fun updatePlaybackState(isPlaying: Boolean, currentPositionMs: Long) {
        val session = mediaSession ?: return
        try {
            val actions = PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_FAST_FORWARD or
                    PlaybackStateCompat.ACTION_REWIND or
                    (if (actionNext != null) PlaybackStateCompat.ACTION_SKIP_TO_NEXT else 0L) or
                    (if (actionPrevious != null) PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS else 0L)

            val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
            val playbackSpeed = if (isPlaying) 1.0f else 0.0f

            val stateBuilder = PlaybackStateCompat.Builder()
                .setActions(actions)
                .setState(
                    state,
                    currentPositionMs.coerceAtLeast(0L),
                    playbackSpeed,
                    SystemClock.elapsedRealtime()
                )

            session.setPlaybackState(stateBuilder.build())
        } catch (e: Exception) {
            Log.e("ClotubeMediaSession", "Error updating playback state: ${e.message}")
        }
    }

    fun release() {
        try {
            mediaSession?.let {
                it.isActive = false
                it.release()
            }
        } catch (e: Exception) {
            Log.e("ClotubeMediaSession", "Error releasing media session: ${e.message}")
        } finally {
            mediaSession = null
        }
    }
}
