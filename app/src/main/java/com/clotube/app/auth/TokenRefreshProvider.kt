package com.clotube.app.auth

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import com.clotube.app.data.LocalStreamProxy
import com.clotube.app.data.TokenRepository
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Coroutine-safe Google Drive OAuth 2.0 access token refresh provider.
 * Supports proactive 45-minute background rotation and reactive recovery for LibVLC video playback,
 * ensuring multi-hour movie streams and binge-watching sessions never drop due to HTTP 401/403.
 */
@Keep
class TokenRefreshProvider(
    private val context: Context,
    private val tokenRepository: TokenRepository? = null,
    private val authRepository: GoogleAuthRepository? = null
) {
    companion object {
        private const val TAG = "TokenRefreshProvider"
        const val DRIVE_BASE_STREAM_URL = "https://www.googleapis.com/drive/v3/files/"
        const val DRIVE_STREAM_PARAMS = "?alt=media&supportsAllDrives=true&acknowledgeAbuse=true"
    }

    private val refreshMutex = Mutex()

    @Volatile
    private var lastKnownToken: String? = null

    /**
     * Clears cached/expired tokens from Google Play Services and LocalStreamProxy on Dispatchers.IO,
     * then requests a fresh OAuth token (ya29...) from Google Play Services.
     */
    suspend fun refreshAccessToken(): String? = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            try {
                Log.d(TAG, "Executing refreshAccessToken() on Dispatchers.IO...")

                // 1. Invalidate local stream proxy cached token
                LocalStreamProxy.invalidateTokenCache()

                // 2. Clear stale token in Google Play Services on-device cache if present
                val tokenToEvict = lastKnownToken
                if (!tokenToEvict.isNullOrBlank()) {
                    try {
                        GoogleAuthUtil.clearToken(context.applicationContext, tokenToEvict)
                        Log.d(TAG, "Cleared cached token from Google Play Services cache")
                    } catch (e: Exception) {
                        Log.w(TAG, "GoogleAuthUtil.clearToken error (non-fatal): ${e.message}")
                    }
                }

                // 3. Request fresh token via TokenRepository (with forceRefresh = true)
                val tokenRepo = tokenRepository ?: TokenRepository(context.applicationContext)
                val freshRepoToken = tokenRepo.getValidAccessToken(forceRefresh = true)
                if (freshRepoToken.isNotBlank()) {
                    lastKnownToken = freshRepoToken
                    Log.i(TAG, "Successfully refreshed OAuth token via TokenRepository")
                    return@withLock freshRepoToken
                }

                // 4. Request fresh token via GoogleAuthRepository
                val authRepo = authRepository ?: GoogleAuthRepository(context.applicationContext)
                val authResult = authRepo.refreshAccessToken()
                val freshAuthToken = authResult.getOrNull()
                if (!freshAuthToken.isNullOrBlank()) {
                    lastKnownToken = freshAuthToken
                    Log.i(TAG, "Successfully refreshed OAuth token via GoogleAuthRepository")
                    return@withLock freshAuthToken
                }

                // 5. Fallback: Direct GoogleAuthUtil token fetch
                val account = GoogleSignIn.getLastSignedInAccount(context.applicationContext)?.account
                if (account != null) {
                    val directToken = GoogleAuthUtil.getToken(
                        context.applicationContext,
                        account,
                        TokenRepository.FULL_DRIVE_SCOPE
                    )
                    if (directToken.isNotBlank()) {
                        lastKnownToken = directToken
                        Log.i(TAG, "Successfully refreshed OAuth token via direct GoogleAuthUtil")
                        return@withLock directToken
                    }
                }

                Log.w(TAG, "refreshAccessToken: Unable to acquire fresh OAuth token")
                null
            } catch (e: Exception) {
                Log.e(TAG, "refreshAccessToken failed: ${e.message}", e)
                null
            }
        }
    }

    /**
     * Retrieves a valid access token. Reuses the in-memory cached token without clearing
     * or making a network call unless forceRefresh is true or token is missing.
     */
    suspend fun getValidAccessToken(forceRefresh: Boolean = false): String? = withContext(Dispatchers.IO) {
        if (!forceRefresh) {
            val cached = lastKnownToken
            if (!cached.isNullOrBlank()) {
                return@withContext cached
            }
            val tokenRepo = tokenRepository ?: TokenRepository(context.applicationContext)
            val repoToken = tokenRepo.getValidAccessToken(forceRefresh = false)
            if (repoToken.isNotBlank()) {
                lastKnownToken = repoToken
                return@withContext repoToken
            }
        }
        refreshAccessToken()
    }

    /**
     * Binds the fresh access token to the active file ID.
     * If an access token is provided, returns the authenticated Drive stream URL;
     * otherwise points to the local HTTP streaming proxy.
     */
    fun buildAuthenticatedStreamUrl(fileId: String, accessToken: String): String {
        if (fileId.isBlank()) return ""
        val token = accessToken.trim()
        return if (token.isNotBlank()) {
            "$DRIVE_BASE_STREAM_URL$fileId$DRIVE_STREAM_PARAMS&access_token=$token"
        } else {
            "http://127.0.0.1:${LocalStreamProxy.PORT}/stream?fileId=$fileId"
        }
    }

    /**
     * Returns LibVLC HTTP header option for passing Bearer token directly.
     */
    fun buildBearerHeaderOption(accessToken: String): String {
        val token = accessToken.trim()
        return if (token.isNotBlank()) {
            ":http-header=Authorization: Bearer $token"
        } else ""
    }
}
