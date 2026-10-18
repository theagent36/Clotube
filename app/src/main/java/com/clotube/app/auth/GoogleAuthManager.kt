package com.clotube.app.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope
import com.google.api.services.drive.DriveScopes

object GoogleAuthManager {
    const val REQUEST_CODE_DRIVE_PERMISSIONS = 9002

    val DRIVE_SCOPE = Scope(DriveScopes.DRIVE)
    val DRIVE_READONLY_SCOPE = Scope(DriveScopes.DRIVE)
    val DRIVE_FILE_SCOPE = Scope(DriveScopes.DRIVE)

    fun getGoogleSignInClient(context: Context): GoogleSignInClient {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            // Request full Drive scope for seamless cross-platform sync
            .requestScopes(Scope(DriveScopes.DRIVE))
            .build()

        return GoogleSignIn.getClient(context, gso)
    }

    fun hasRequiredScopes(context: Context): Boolean {
        val account = GoogleSignIn.getLastSignedInAccount(context) ?: return false
        return GoogleSignIn.hasPermissions(account, Scope(DriveScopes.DRIVE))
    }

    /**
     * Checks if full Drive scope is active on the account.
     */
    fun hasAllDrivePermissions(context: Context, account: GoogleSignInAccount? = null): Boolean {
        val acc = account ?: GoogleSignIn.getLastSignedInAccount(context) ?: return false
        return GoogleSignIn.hasPermissions(acc, Scope(DriveScopes.DRIVE))
    }

    /**
     * Triggers the interactive permission consent dialog if full Drive scope is missing.
     */
    fun requestMissingPermissions(activity: Activity, requestCode: Int = REQUEST_CODE_DRIVE_PERMISSIONS) {
        val account = GoogleSignIn.getLastSignedInAccount(activity) ?: return
        GoogleSignIn.requestPermissions(
            activity,
            requestCode,
            account,
            Scope(DriveScopes.DRIVE)
        )
    }

    fun signOut(context: Context, onComplete: () -> Unit = {}) {
        getGoogleSignInClient(context).signOut().addOnCompleteListener {
            onComplete()
        }
    }

    fun revokeAccess(context: Context, onComplete: () -> Unit = {}) {
        getGoogleSignInClient(context).revokeAccess().addOnCompleteListener {
            onComplete()
        }
    }

    /**
     * Forces fresh sign-in dialog by calling signOut() prior to launching signInIntent.
     * Prevents Google Play Services from silently re-authorizing with stale/expired credentials.
     */
    fun launchGoogleSignIn(activity: Activity, launcher: ActivityResultLauncher<Intent>) {
        val client = getGoogleSignInClient(activity)
        client.signOut().addOnCompleteListener {
            val signInIntent = client.signInIntent
            launcher.launch(signInIntent)
        }
    }

    fun launchGoogleSignIn(context: Context, launcher: ActivityResultLauncher<Intent>) {
        val client = getGoogleSignInClient(context)
        client.signOut().addOnCompleteListener {
            val signInIntent = client.signInIntent
            launcher.launch(signInIntent)
        }
    }
}
