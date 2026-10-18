package com.clotube.app.auth

import android.accounts.Account
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Authentication & Token Resolution Repository for Clotube.
 * Securely negotiates Google Sign-In and OAuth 2.0 Bearer access tokens (ya29.*)
 * with automatic token expiration refresh handling.
 */
class GoogleAuthRepository(private val context: Context) {

    companion object {
        private const val TAG = "ClotubeAuth"
        const val DRIVE_FULL_SCOPE = "https://www.googleapis.com/auth/drive"
        const val DRIVE_READONLY_SCOPE = "https://www.googleapis.com/auth/drive.readonly"
        const val DRIVE_FILE_SCOPE = "https://www.googleapis.com/auth/drive.file"
        private const val OAUTH_SCOPE_PREFIX = "oauth2:"
    }

    private val googleSignInClient: GoogleSignInClient by lazy {
        GoogleAuthManager.getGoogleSignInClient(context)
    }

    private val _currentAccount = MutableStateFlow<GoogleSignInAccount?>(
        GoogleSignIn.getLastSignedInAccount(context)
    )
    val currentAccount: StateFlow<GoogleSignInAccount?> = _currentAccount.asStateFlow()

    private val _accessToken = MutableStateFlow<String?>(null)
    val accessToken: StateFlow<String?> = _accessToken.asStateFlow()

    fun setCurrentAccount(account: GoogleSignInAccount?) {
        _currentAccount.value = account
    }

    fun getSignInIntent(): Intent {
        return googleSignInClient.signInIntent
    }

    fun handleSignInResult(data: Intent?): Result<GoogleSignInAccount> {
        return try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)
            _currentAccount.value = account
            Log.d(TAG, "Signed in successfully as ${account.email}")
            Result.success(account)
        } catch (e: Exception) {
            Log.e(TAG, "Google Sign-In failed", e)
            Result.failure(e)
        }
    }

    /**
     * Resolves a valid OAuth 2.0 Bearer token (starting with ya29.) on Dispatchers.IO.
     */
    suspend fun getAccessToken(): Result<String> = withContext(Dispatchers.IO) {
        val account = _currentAccount.value?.account ?: run {
            val last = GoogleSignIn.getLastSignedInAccount(context)
            if (last != null) {
                _currentAccount.value = last
                last.account
            } else {
                null
            }
        }

        if (account == null) {
            return@withContext Result.failure(IllegalStateException("No Google account signed in."))
        }

        // 1. Try full Drive scope
        try {
            val scopeString = "$OAUTH_SCOPE_PREFIX$DRIVE_FULL_SCOPE"
            val token = GoogleAuthUtil.getToken(context, account, scopeString)
            _accessToken.value = token
            Log.d(TAG, "Successfully acquired full Drive OAuth 2.0 Bearer token (starts with ${token.take(6)}...)")
            return@withContext Result.success(token)
        } catch (e: Exception) {
            Log.w(TAG, "Full Drive scope token attempt: ${e.message}, trying fallback scopes...")
        }

        // 2. Try combined scopes (Read media + Write watch history JSON)
        try {
            val scopeString = "$OAUTH_SCOPE_PREFIX$DRIVE_READONLY_SCOPE $DRIVE_FILE_SCOPE"
            val token = GoogleAuthUtil.getToken(context, account, scopeString)
            _accessToken.value = token
            Log.d(TAG, "Successfully acquired OAuth 2.0 Bearer token (starts with ${token.take(6)}...)")
            return@withContext Result.success(token)
        } catch (e: Exception) {
            Log.w(TAG, "Combined scope token failed (${e.message}), falling back to drive.readonly so streaming works")
        }

        // 3. Guaranteed Fallback: Read-only scope (already approved by user)
        try {
            val readOnlyScope = "$OAUTH_SCOPE_PREFIX$DRIVE_READONLY_SCOPE"
            val token = GoogleAuthUtil.getToken(context, account, readOnlyScope)
            _accessToken.value = token
            Log.d(TAG, "Successfully acquired read-only OAuth 2.0 Bearer token (starts with ${token.take(6)}...)")
            Result.success(token)
        } catch (recoverable: UserRecoverableAuthException) {
            Log.w(TAG, "User recoverable auth exception encountered on fallback scope", recoverable)
            Result.failure(recoverable)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve access token with fallback scope", e)
            Result.failure(e)
        }
    }

    /**
     * Clears an expired token from Google Play Services cache and retrieves a fresh token.
     */
    suspend fun refreshAccessToken(): Result<String> = withContext(Dispatchers.IO) {
        val oldToken = _accessToken.value
        val account = _currentAccount.value?.account ?: run {
            GoogleSignIn.getLastSignedInAccount(context)?.account
        }

        if (account == null) {
            return@withContext Result.failure(IllegalStateException("No Google account signed in."))
        }

        try {
            if (!oldToken.isNullOrBlank()) {
                Log.d(TAG, "Invalidating expired OAuth token in Play Services cache...")
                GoogleAuthUtil.clearToken(context, oldToken)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to clear token in Play Services cache: ${e.message}")
        }

        // 1. Try full Drive scope
        try {
            val scopeString = "$OAUTH_SCOPE_PREFIX$DRIVE_FULL_SCOPE"
            val freshToken = GoogleAuthUtil.getToken(context, account, scopeString)
            _accessToken.value = freshToken
            Log.d(TAG, "Successfully refreshed full Drive OAuth 2.0 Bearer token")
            return@withContext Result.success(freshToken)
        } catch (e: Exception) {
            Log.w(TAG, "Full Drive scope refresh failed: ${e.message}, trying fallback scopes...")
        }

        // 2. Try combined scopes
        try {
            val scopeString = "$OAUTH_SCOPE_PREFIX$DRIVE_READONLY_SCOPE $DRIVE_FILE_SCOPE"
            val freshToken = GoogleAuthUtil.getToken(context, account, scopeString)
            _accessToken.value = freshToken
            Log.d(TAG, "Successfully refreshed OAuth 2.0 Bearer token")
            return@withContext Result.success(freshToken)
        } catch (e: Exception) {
            Log.w(TAG, "Refresh combined scope token failed (${e.message}), falling back to drive.readonly")
        }

        // 3. Guaranteed Fallback: Read-only scope
        try {
            val readOnlyScope = "$OAUTH_SCOPE_PREFIX$DRIVE_READONLY_SCOPE"
            val freshToken = GoogleAuthUtil.getToken(context, account, readOnlyScope)
            _accessToken.value = freshToken
            Log.d(TAG, "Successfully refreshed read-only OAuth 2.0 Bearer token")
            Result.success(freshToken)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to refresh OAuth token on fallback scope", e)
            Result.failure(e)
        }
    }

    fun hasDriveFilePermission(account: GoogleSignInAccount? = null): Boolean {
        return GoogleAuthManager.hasAllDrivePermissions(context, account)
    }

    fun signOut(onComplete: () -> Unit) {
        GoogleAuthManager.signOut(context) {
            _currentAccount.value = null
            _accessToken.value = null
            onComplete()
        }
    }
}
