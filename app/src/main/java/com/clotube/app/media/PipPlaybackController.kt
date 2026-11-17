package com.clotube.app.media

import android.app.Activity
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import android.util.Rational
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages native Android Picture-in-Picture (PiP) state, 16:9 aspect ratio configuration,
 * interactive RemoteAction controls (Rewind 10s, Play/Pause, Forward 10s), and automatic
 * transition handling for Android 8.0+ (API 26+) and Android 12+ (API 31+).
 */
@Keep
object PipPlaybackController {

    private const val TAG = "PipPlaybackController"

    const val ACTION_PIP_MEDIA_CONTROL = "com.clotube.app.PIP_MEDIA_CONTROL"
    const val EXTRA_CONTROL_TYPE = "control_type"

    const val CONTROL_REWIND = 1
    const val CONTROL_PLAY_PAUSE = 2
    const val CONTROL_FORWARD = 3

    /**
     * Whether a video is currently actively playing in the foreground player.
     * Determines whether gesture navigation auto-enters PiP and drives action icon states.
     */
    var isVideoActivelyPlaying by mutableStateOf(false)

    /**
     * Reactive StateFlow reflecting whether the host Activity is currently inside PiP window.
     * When true, Clotube video player hides all HUD chrome and overlays.
     */
    private val _isInPipMode = MutableStateFlow(false)
    val isInPipMode: StateFlow<Boolean> = _isInPipMode.asStateFlow()

    fun setInPipMode(inPip: Boolean) {
        _isInPipMode.value = inPip
    }

    // Active player control delegates
    var onPlayPause: (() -> Unit)? = null
    var onRewind10: (() -> Unit)? = null
    var onForward10: (() -> Unit)? = null

    private var currentActivityRef: java.lang.ref.WeakReference<Activity>? = null
    private var isReceiverRegistered = false

    private val pipBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_PIP_MEDIA_CONTROL) return

            when (intent.getIntExtra(EXTRA_CONTROL_TYPE, 0)) {
                CONTROL_REWIND -> {
                    Log.d(TAG, "PiP RemoteAction -> Rewind 10s")
                    onRewind10?.invoke()
                }
                CONTROL_PLAY_PAUSE -> {
                    Log.d(TAG, "PiP RemoteAction -> Play/Pause")
                    onPlayPause?.invoke()
                    currentActivityRef?.get()?.let { act ->
                        updatePipParams(act)
                    }
                }
                CONTROL_FORWARD -> {
                    Log.d(TAG, "PiP RemoteAction -> Forward 10s")
                    onForward10?.invoke()
                }
            }
        }
    }

    /**
     * Registers the broadcast receiver for PiP RemoteAction buttons.
     */
    fun register(context: Context, activity: Activity? = null) {
        if (activity != null) {
            currentActivityRef = java.lang.ref.WeakReference(activity)
        }
        if (isReceiverRegistered) return

        try {
            val filter = IntentFilter(ACTION_PIP_MEDIA_CONTROL)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.applicationContext.registerReceiver(
                    pipBroadcastReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                context.applicationContext.registerReceiver(
                    pipBroadcastReceiver,
                    filter
                )
            }
            isReceiverRegistered = true
            Log.d(TAG, "PiP BroadcastReceiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register PiP receiver: ${e.message}", e)
        }
    }

    /**
     * Unregisters the broadcast receiver when Activity is destroyed.
     */
    fun unregister(context: Context) {
        if (!isReceiverRegistered) return
        try {
            context.applicationContext.unregisterReceiver(pipBroadcastReceiver)
            isReceiverRegistered = false
            currentActivityRef = null
            Log.d(TAG, "PiP BroadcastReceiver unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister PiP receiver: ${e.message}", e)
        }
    }

    /**
     * Binds active player callbacks.
     */
    fun bindPlayerCallbacks(
        activity: Activity,
        onTogglePlayPause: () -> Unit,
        onSeekRewind: () -> Unit,
        onSeekForward: () -> Unit
    ) {
        currentActivityRef = java.lang.ref.WeakReference(activity)
        onPlayPause = onTogglePlayPause
        onRewind10 = onSeekRewind
        onForward10 = onSeekForward
        updatePipParams(activity)
    }

    /**
     * Unbinds player callbacks on dispose.
     */
    fun unbindPlayerCallbacks() {
        onPlayPause = null
        onRewind10 = null
        onForward10 = null
        isVideoActivelyPlaying = false
    }

    /**
     * Builds PictureInPictureParams for Android O+ (API 26+).
     */
    @RequiresApi(Build.VERSION_CODES.O)
    fun buildPipParams(context: Context, isPlaying: Boolean = isVideoActivelyPlaying): PictureInPictureParams {
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))

        val actions = mutableListOf<RemoteAction>()

        // 1. Rewind 10 seconds action
        val rewindIntent = PendingIntent.getBroadcast(
            context,
            CONTROL_REWIND,
            Intent(ACTION_PIP_MEDIA_CONTROL).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_CONTROL_TYPE, CONTROL_REWIND)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        actions.add(
            RemoteAction(
                Icon.createWithResource(context, android.R.drawable.ic_media_rew),
                "Rewind 10s",
                "Rewind 10s",
                rewindIntent
            )
        )

        // 2. Play / Pause action
        val playPauseIntent = PendingIntent.getBroadcast(
            context,
            CONTROL_PLAY_PAUSE,
            Intent(ACTION_PIP_MEDIA_CONTROL).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_CONTROL_TYPE, CONTROL_PLAY_PAUSE)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val iconRes = if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        val title = if (isPlaying) "Pause" else "Play"
        actions.add(
            RemoteAction(
                Icon.createWithResource(context, iconRes),
                title,
                title,
                playPauseIntent
            )
        )

        // 3. Forward 10 seconds action
        val forwardIntent = PendingIntent.getBroadcast(
            context,
            CONTROL_FORWARD,
            Intent(ACTION_PIP_MEDIA_CONTROL).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_CONTROL_TYPE, CONTROL_FORWARD)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        actions.add(
            RemoteAction(
                Icon.createWithResource(context, android.R.drawable.ic_media_ff),
                "Forward 10s",
                "Forward 10s",
                forwardIntent
            )
        )

        builder.setActions(actions)

        // Smooth Auto-Enter for gesture navigation on Android 12+ (API 31+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(isVideoActivelyPlaying)
        }

        return builder.build()
    }

    /**
     * Updates host Activity PiP params dynamically (e.g. when play/pause state flips).
     */
    fun updatePipParams(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                activity.setPictureInPictureParams(buildPipParams(activity, isVideoActivelyPlaying))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update PiP params: ${e.message}")
            }
        }
    }
}
