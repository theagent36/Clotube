package com.clotube.app.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

class ClotubeAudioFocusManager(
    context: Context,
    private var onPausePlayback: (() -> Unit)? = null,
    private var onResumePlayback: (() -> Unit)? = null
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var audioFocusRequest: AudioFocusRequest? = null
    @Volatile
    private var isReleased = false

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        if (isReleased) return@OnAudioFocusChangeListener
        mainHandler.post {
            if (isReleased) return@post
            try {
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                        Log.d("ClotubeAudioFocus", "Audio focus lost ($focusChange). Pausing playback safely.")
                        onPausePlayback?.invoke()
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        Log.d("ClotubeAudioFocus", "Audio focus gained. Resuming playback safely.")
                        onResumePlayback?.invoke()
                    }
                }
            } catch (t: Throwable) {
                Log.w("ClotubeAudioFocus", "Safe focus change notice: ${t.message}")
            }
        }
    }

    fun setPlaybackCallbacks(
        onPause: () -> Unit,
        onResume: () -> Unit
    ) {
        if (isReleased) return
        this.onPausePlayback = onPause
        this.onResumePlayback = onResume
    }

    fun cancelPendingResume() {
        // Safe no-op when focus callbacks are handled atomically
    }

    fun requestAudioFocus(): Boolean {
        if (isReleased || audioManager == null) return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val playbackAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()

                val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(playbackAttributes)
                    .setAcceptsDelayedFocusGain(false)
                    .setOnAudioFocusChangeListener(focusChangeListener, mainHandler)
                    .build()

                audioFocusRequest = request
                audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    focusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN
                ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            }
        } catch (t: Throwable) {
            Log.w("ClotubeAudioFocus", "Request focus error: ${t.message}")
            false
        }
    }

    fun abandonFocus() {
        isReleased = true
        mainHandler.removeCallbacksAndMessages(null)
        onPausePlayback = null
        onResumePlayback = null

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager?.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager?.abandonAudioFocus(focusChangeListener)
            }
        } catch (_: Throwable) {}
    }

    fun abandonAudioFocus() {
        abandonFocus()
    }

    fun release() {
        abandonFocus()
    }
}
