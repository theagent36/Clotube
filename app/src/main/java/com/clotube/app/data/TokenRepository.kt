package com.clotube.app.data

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TokenRepository(private val context: Context) {
    companion object {
        private const val TAG = "TokenRepo"
        const val FULL_DRIVE_SCOPE = "oauth2:https://www.googleapis.com/auth/drive"
        const val COMBINED_SCOPES = "oauth2:https://www.googleapis.com/auth/drive.readonly https://www.googleapis.com/auth/drive.file"
        const val READ_ONLY_SCOPE = "oauth2:https://www.googleapis.com/auth/drive.readonly"
    }

    @Volatile
    private var lastKnownToken: String = ""
    @Volatile
    private var lastTokenFetchTimeMs: Long = 0L

    @Volatile
    var recoveryIntentLauncher: ((Intent) -> Unit)? = null

    suspend fun getValidAccessToken(forceRefresh: Boolean = false): String = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!forceRefresh && lastKnownToken.isNotBlank() && (now - lastTokenFetchTimeMs < 50 * 60 * 1000L)) {
            Log.d(TAG, "getValidAccessToken: Returning in-memory cached token (age=${(now - lastTokenFetchTimeMs) / 1000}s)")
            return@withContext lastKnownToken
        }

        val account = GoogleSignIn.getLastSignedInAccount(context)?.account
        if (account == null) {
            Log.w(TAG, "getValidAccessToken: No signed-in Google account found")
            return@withContext ""
        }

        if (forceRefresh && lastKnownToken.isNotBlank()) {
            try {
                GoogleAuthUtil.clearToken(context, lastKnownToken)
            } catch (ignored: Exception) {}
            lastKnownToken = ""
            lastTokenFetchTimeMs = 0L
        }

        // 1. Try full Drive scope
        try {
            val token = GoogleAuthUtil.getToken(context, account, FULL_DRIVE_SCOPE)
            lastKnownToken = token
            lastTokenFetchTimeMs = now
            return@withContext token
        } catch (e: UserRecoverableAuthException) {
            Log.w(TAG, "User consent required for full Drive scope. Triggering recovery intent launcher...")
            e.intent?.let { intent ->
                withContext(Dispatchers.Main) {
                    recoveryIntentLauncher?.invoke(intent)
                }
            }
            return@withContext ""
        } catch (e: Exception) {
            Log.d(TAG, "Full Drive token attempt: ${e.message}. Trying combined scopes...")
        }

        // 2. Try combined scopes
        try {
            val token = GoogleAuthUtil.getToken(context, account, COMBINED_SCOPES)
            lastKnownToken = token
            lastTokenFetchTimeMs = now
            token
        } catch (e: UserRecoverableAuthException) {
            Log.w(TAG, "User consent required for scopes. Triggering recovery intent launcher...")
            e.intent?.let { intent ->
                withContext(Dispatchers.Main) {
                    recoveryIntentLauncher?.invoke(intent)
                }
            }
            ""
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching combined token: ${e.message}")
            try {
                val readToken = GoogleAuthUtil.getToken(context, account, READ_ONLY_SCOPE)
                lastKnownToken = readToken
                lastTokenFetchTimeMs = now
                readToken
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback read-only token failed: ${e2.message}")
                ""
            }
        }
    }

    /**
     * Purges the dead/revoked token from Google Play Services local device cache
     * and forces an interactive consent prompt.
     */
    suspend fun invalidateAndForceConsent() = withContext(Dispatchers.IO) {
        val account = GoogleSignIn.getLastSignedInAccount(context)?.account ?: return@withContext
        if (lastKnownToken.isNotBlank()) {
            try {
                GoogleAuthUtil.clearToken(context, lastKnownToken)
                Log.i(TAG, "Cleared stale token from Google Play Services cache")
            } catch (e: Exception) {
                Log.e(TAG, "clearToken error: ${e.message}")
            }
            lastKnownToken = ""
        }

        // Calling getToken right after clearToken forces Play Services to contact Google servers,
        // which throws UserRecoverableAuthException and launches the consent checkboxes.
        try {
            val freshToken = GoogleAuthUtil.getToken(context, account, FULL_DRIVE_SCOPE)
            lastKnownToken = freshToken
        } catch (e: UserRecoverableAuthException) {
            Log.i(TAG, "Launching consent dialog for freshly cleared token...")
            e.intent?.let { intent ->
                withContext(Dispatchers.Main) {
                    recoveryIntentLauncher?.invoke(intent)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to re-fetch token after invalidation: ${e.message}")
        }
    }

    suspend fun invalidateToken(token: String) = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext
        try {
            GoogleAuthUtil.clearToken(context, token)
            Log.i(TAG, "Successfully cleared invalid/stale token from Google Play Services")
            if (lastKnownToken == token) {
                lastKnownToken = ""
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear token: ${e.message}")
        }
    }
}
