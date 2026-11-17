package com.clotube.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object ClotubeNetworkMonitor {

    private const val TAG = "ClotubeNetwork"
    private const val WAKE_GRACE_DELAY_MS = 2200L
    private const val OFFLINE_DEBOUNCE_DELAY_MS = 2500L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val isStarted = AtomicBoolean(false)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var isAppForegrounded: Boolean = true

    private val _isOnline = MutableStateFlow(true)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _isChecking = MutableStateFlow(false)
    val isChecking: StateFlow<Boolean> = _isChecking.asStateFlow()

    private var offlineEvaluationJob: Job? = null
    private var foregroundGraceJob: Job? = null

    private val probeClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .writeTimeout(4, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    private fun isDeviceScreenInteractive(context: Context?): Boolean {
        val pm = context?.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isInteractive
    }

    /**
     * Evaluates system network capabilities without blocking sockets.
     * Returns Pair(hasInternetCapability, isOsValidated).
     */
    private fun evaluateSystemNetwork(context: Context?): Pair<Boolean, Boolean> {
        val cm = context?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return Pair(true, true)
        val activeNetwork = cm.activeNetwork ?: return Pair(false, false)
        val caps = cm.getNetworkCapabilities(activeNetwork) ?: return Pair(false, false)
        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return Pair(hasInternet, isValidated)
    }

    /**
     * Carrier-safe HTTPS 204 reachability check (works across 5G/LTE/Wi-Fi where TCP port 53 is blocked).
     */
    private suspend fun probeHttpsReachability(context: Context?): Boolean = withContext(Dispatchers.IO) {
        val (hasInternet, isValidated) = evaluateSystemNetwork(context)
        if (!hasInternet) return@withContext false
        if (isValidated) return@withContext true

        val endpoints = listOf(
            "https://connectivitycheck.gstatic.com/generate_204",
            "https://www.google.com/generate_204"
        )
        for (url in endpoints) {
            try {
                val request = Request.Builder()
                    .url(url)
                    .header("Cache-Control", "no-cache")
                    .header("Connection", "close")
                    .get()
                    .build()
                probeClient.newCall(request).execute().use { response ->
                    if (response.code == 204 || response.isSuccessful) {
                        return@withContext true
                    }
                }
            } catch (_: Exception) {
                // Try fallback endpoint
            }
        }
        false
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            offlineEvaluationJob?.cancel()
            val ctx = appContext
            val (hasInternet, _) = evaluateSystemNetwork(ctx)
            if (hasInternet) {
                _isOnline.value = true
            }
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val isValidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

            if (hasInternet && isValidated) {
                offlineEvaluationJob?.cancel()
                _isOnline.value = true
                return
            }

            // Do NOT mark offline while backgrounded or screen-locked (OS revokes UID network access in Doze/Standby)
            if (!isAppForegrounded || !isDeviceScreenInteractive(appContext)) {
                return
            }

            if (!hasInternet) {
                scheduleConfirmedOfflineCheck("capabilities_no_internet")
            }
        }

        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
            if (blocked) {
                // Android blocks network per-UID when backgrounded or locked — ignore unless actively foregrounded
                if (!isAppForegrounded || !isDeviceScreenInteractive(appContext)) {
                    Log.d(TAG, "Ignoring background/locked UID network block callback")
                    return
                }
                scheduleConfirmedOfflineCheck("uid_blocked_foreground")
            } else {
                offlineEvaluationJob?.cancel()
                val (hasInternet, _) = evaluateSystemNetwork(appContext)
                if (hasInternet) {
                    _isOnline.value = true
                }
            }
        }

        override fun onLost(network: Network) {
            // Ignore network loss events triggered by screen lock or background standby
            if (!isAppForegrounded || !isDeviceScreenInteractive(appContext)) {
                Log.d(TAG, "Ignoring onLost while app is backgrounded/locked")
                return
            }
            scheduleConfirmedOfflineCheck("network_lost")
        }
    }

    private fun scheduleConfirmedOfflineCheck(reason: String) {
        if (!isAppForegrounded || !isDeviceScreenInteractive(appContext)) return
        offlineEvaluationJob?.cancel()
        offlineEvaluationJob = scope.launch {
            delay(OFFLINE_DEBOUNCE_DELAY_MS)
            if (!isAppForegrounded || !isDeviceScreenInteractive(appContext)) return@launch

            // Attempt 1
            val firstCheck = probeHttpsReachability(appContext)
            if (firstCheck) {
                _isOnline.value = true
                return@launch
            }

            // Wait 1.5s for cellular/Wi-Fi radio wake-up and confirm with Attempt 2
            delay(1500L)
            if (!isAppForegrounded || !isDeviceScreenInteractive(appContext)) return@launch

            val secondCheck = probeHttpsReachability(appContext)
            _isOnline.value = secondCheck
            if (!secondCheck) {
                Log.w(TAG, "Confirmed offline state after dual-probe ($reason)")
            }
        }
    }

    fun startMonitoring(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        val (hasInternet, _) = evaluateSystemNetwork(appCtx)
        _isOnline.value = hasInternet

        if (isStarted.compareAndSet(false, true)) {
            try {
                val cm = appCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm?.registerDefaultNetworkCallback(networkCallback)
                Log.d(TAG, "Registered foreground-aware default network callback (initialOnline=$hasInternet)")
            } catch (e: Exception) {
                isStarted.set(false)
                Log.e(TAG, "Failed to register network callback: ${e.message}")
            }
        }
    }

    /**
     * Called from MainActivity.onStart() / onResume() when user unlocks device or returns from background.
     */
    fun onAppForegrounded(context: Context? = null) {
        context?.applicationContext?.let { appContext = it }
        isAppForegrounded = true
        offlineEvaluationJob?.cancel()
        foregroundGraceJob?.cancel()

        val (hasInternet, isValidated) = evaluateSystemNetwork(appContext)
        if (hasInternet) {
            // Optimistically mark online immediately on unlock/resume while radio wakes up
            _isOnline.value = true
        }

        if (hasInternet && isValidated) {
            return
        }

        // Give cellular/Wi-Fi modem 2.2s to wake from screen-off sleep before checking
        foregroundGraceJob = scope.launch {
            delay(WAKE_GRACE_DELAY_MS)
            if (!isAppForegrounded) return@launch
            val reachable = probeHttpsReachability(appContext)
            if (reachable) {
                _isOnline.value = true
            } else {
                scheduleConfirmedOfflineCheck("foreground_wake_check")
            }
        }
    }

    /**
     * Called from MainActivity.onStop() when screen locks or app moves to background.
     */
    fun onAppBackgrounded() {
        isAppForegrounded = false
        offlineEvaluationJob?.cancel()
        foregroundGraceJob?.cancel()
    }

    suspend fun recheckNow(): Boolean {
        _isChecking.value = true
        return try {
            val reachable = probeHttpsReachability(appContext)
            _isOnline.value = reachable
            reachable
        } finally {
            _isChecking.value = false
        }
    }
}
