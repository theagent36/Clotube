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
    private var onPausePlayback: () -> Unit = {},
    private var onResumePlayback: () -> Unit = {}
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false
    private var resumeOnFocusGain = false

    fun setPlaybackCallbacks(
        onPause: () -> Unit,
        onResume: () -> Unit
    ) {
        this.onPausePlayback = onPause
        this.onResumePlayback = onResume
    }

    fun cancelPendingResume() {
        resumeOnFocusGain = false
    }

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        mainHandler.post {
            Log.d("ClotubeAudioFocus", "onAudioFocusChange: $focusChange")
            when (focusChange) {
                AudioManager.AUDIOFOCUS_LOSS -> {
                    // Permanent loss (another app started playback)
                    hasAudioFocus = false
                    resumeOnFocusGain = false
                    onPausePlayback()
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    // Temporary loss (incoming phone call ringing or voice notification)
                    hasAudioFocus = false
                    resumeOnFocusGain = true
                    onPausePlayback()
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    // Call ended or rejected; regain audio focus
                    hasAudioFocus = true
                    if (resumeOnFocusGain) {
                        resumeOnFocusGain = false
                        onResumePlayback()
                    }
                }
            }
        }
    }

    fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()

            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(true)
                .setOnAudioFocusChangeListener(focusChangeListener, mainHandler)
                .build()

            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }

        hasAudioFocus = (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
        return hasAudioFocus
    }

    fun abandonAudioFocus() {
        resumeOnFocusGain = false
        if (!hasAudioFocus && audioFocusRequest == null) return
        hasAudioFocus = false

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
                audioFocusRequest = null
            } else {
                @Suppress("DEPRECATION")
                audioManager.abandonAudioFocus(focusChangeListener)
            }
        } catch (e: Exception) {
            Log.e("ClotubeAudioFocus", "Error abandoning audio focus: ${e.message}")
        }
    }
}
