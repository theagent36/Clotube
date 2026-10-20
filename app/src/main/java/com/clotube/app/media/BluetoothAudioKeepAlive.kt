package com.clotube.app.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import androidx.annotation.Keep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight, lifecycle-safe keep-alive AudioTrack that writes zeroed PCM silence frames.
 *
 * Prevents Bluetooth A2DP audio sinks from dropping into low-power standby during pauses,
 * eliminating the 500-900ms hardware wake-up latency and subsequent 1-second audio mute/cutout
 * when video playback resumes.
 */
@Keep
class BluetoothAudioKeepAlive(
    private val coroutineScope: CoroutineScope
) {
    companion object {
        private const val TAG = "BluetoothKeepAlive"
        private const val SAMPLE_RATE = 44100
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_OUT_STEREO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }

    private var audioTrack: AudioTrack? = null
    private var feedingJob: Job? = null
    private val isRunning = AtomicBoolean(false)
    private var silenceBuffer: ByteArray = ByteArray(0)

    @Synchronized
    fun start() {
        if (isRunning.getAndSet(true)) return

        try {
            val minBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
                .coerceAtLeast(4096)
            silenceBuffer = ByteArray(minBufferSize)

            val track = audioTrack?.takeIf { it.state == AudioTrack.STATE_INITIALIZED }
                ?: run {
                    val attributes = AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .build()

                    val format = AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(CHANNEL_CONFIG)
                        .setEncoding(AUDIO_FORMAT)
                        .build()

                    AudioTrack.Builder()
                        .setAudioAttributes(attributes)
                        .setAudioFormat(format)
                        .setBufferSizeInBytes(minBufferSize)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                }

            try {
                track.setVolume(0.0f)
            } catch (_: Exception) {}

            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.play()
            }
            audioTrack = track

            feedingJob?.cancel()
            feedingJob = coroutineScope.launch(Dispatchers.IO) {
                while (isActive && isRunning.get()) {
                    val currentTrack = audioTrack ?: break
                    if (currentTrack.playState == AudioTrack.PLAYSTATE_PLAYING) {
                        val written = try {
                            currentTrack.write(
                                silenceBuffer,
                                0,
                                silenceBuffer.size,
                                AudioTrack.WRITE_NON_BLOCKING
                            )
                        } catch (_: Exception) {
                            -1
                        }

                        if (written <= 0) {
                            delay(20L)
                        }
                    } else {
                        delay(50L)
                    }
                }
            }
            Log.d(TAG, "BluetoothAudioKeepAlive started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start BluetoothAudioKeepAlive: ${e.message}", e)
            isRunning.set(false)
        }
    }

    @Synchronized
    fun stop() {
        if (!isRunning.getAndSet(false)) return
        feedingJob?.cancel()
        feedingJob = null
        try {
            audioTrack?.pause()
            audioTrack?.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error pausing AudioTrack: ${e.message}")
        }
    }

    @Synchronized
    fun release() {
        isRunning.set(false)
        feedingJob?.cancel()
        feedingJob = null
        try {
            audioTrack?.stop()
        } catch (_: Exception) {}
        try {
            audioTrack?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing AudioTrack: ${e.message}")
        }
        audioTrack = null
        Log.d(TAG, "BluetoothAudioKeepAlive released")
    }
}
