package com.clotube.app.media

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.Keep
import org.videolan.libvlc.Media

/**
 * Global LibVLC Media configuration helper ensuring consistent network caching,
 * resilience against transient socket drops, and start-time seeking across all video players.
 */
@Keep
fun Media.applyClotubeMediaOptions(startTimeSeconds: Long = 0L) {
    addOption(":network-caching=1500")
    addOption(":file-caching=1500")
    addOption(":live-caching=1500")
    addOption(":clock-jitter=0")
    addOption(":clock-synchro=0")
    addOption("--no-sub-autodetect-file")
    if (startTimeSeconds > 0L) {
        addOption(":start-time=$startTimeSeconds")
    }
}

/**
 * Lifecycle-aware network monitor tracking active network interfaces and disconnects.
 * Dispatches debounced (1500ms) onNetworkHandover callbacks when the transport switches
 * (e.g., Wi-Fi <-> Mobile Data) or reconnects after signal drop, only once playback has started.
 */
@Keep
class NetworkMonitor(
    context: Context,
    private val hasRenderedFirstFrame: () -> Boolean = { true },
    private val onNetworkHandover: () -> Unit
) {
    companion object {
        private const val TAG = "NetworkMonitor"
        private const val DEBOUNCE_DELAY_MS = 1500L
    }

    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val mainHandler = Handler(Looper.getMainLooper())
    private var debounceRunnable: Runnable? = null

    private var activeNetwork: Network? = null
    private var wasDisconnected: Boolean = false
    private var isRegistered: Boolean = false
    private var initialNetworkRecorded: Boolean = false

    val isConnected: Boolean
        get() {
            val cm = connectivityManager ?: return false
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (!initialNetworkRecorded) {
                activeNetwork = network
                initialNetworkRecorded = true
                Log.d(TAG, "Initial network recorded on first onAvailable: $network. Suppressing initial handover.")
                return
            }

            val previousNetwork = activeNetwork
            val hadDisconnect = wasDisconnected
            val isGenuineChange = (previousNetwork != null && previousNetwork != network) || hadDisconnect

            activeNetwork = network
            wasDisconnected = false

            if (isGenuineChange && hasRenderedFirstFrame()) {
                Log.d(TAG, "Network handover/reconnection detected (prev=$previousNetwork, new=$network, hadDisconnect=$hadDisconnect). Debouncing ${DEBOUNCE_DELAY_MS}ms...")
                debounceRunnable?.let { mainHandler.removeCallbacks(it) }
                val runnable = Runnable {
                    if (hasRenderedFirstFrame()) {
                        Log.i(TAG, "Invoking onNetworkHandover on Main thread")
                        onNetworkHandover()
                    }
                }
                debounceRunnable = runnable
                mainHandler.postDelayed(runnable, DEBOUNCE_DELAY_MS)
            }
        }

        override fun onLost(network: Network) {
            if (activeNetwork == network) {
                wasDisconnected = true
                activeNetwork = null
                Log.d(TAG, "Active network lost: $network. wasDisconnected marked true")
            }
        }
    }

    fun register() {
        if (isRegistered || connectivityManager == null) return
        try {
            activeNetwork = connectivityManager.activeNetwork
            initialNetworkRecorded = (activeNetwork != null)
            wasDisconnected = (activeNetwork == null)

            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, networkCallback)
            isRegistered = true
            Log.d(TAG, "NetworkCallback successfully registered (initialNetworkRecorded=$initialNetworkRecorded)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register NetworkCallback: ${e.message}", e)
        }
    }

    fun unregister() {
        if (!isRegistered || connectivityManager == null) return
        debounceRunnable?.let { mainHandler.removeCallbacks(it) }
        debounceRunnable = null
        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            Log.d(TAG, "NetworkCallback successfully unregistered")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to unregister NetworkCallback: ${e.message}", e)
        } finally {
            isRegistered = false
            initialNetworkRecorded = false
        }
    }
}
