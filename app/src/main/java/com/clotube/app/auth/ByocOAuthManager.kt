package com.clotube.app.auth

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.annotation.Keep
import androidx.browser.customtabs.CustomTabsIntent
import com.clotube.app.data.ByocConfigManager
import com.clotube.app.data.ByocOAuthConfig
import com.clotube.app.data.ByocSessionTokens
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Coroutine-safe OAuth 2.0 Loopback (http://127.0.0.1) + PKCE manager for Bring Your Own Cloud (BYOC).
 * Executes RFC 8252 Authorization Code Grant on an ephemeral loopback ServerSocket,
 * exchanges authorization codes for long-lived tokens, hydrates user profiles,
 * and handles proactive/reactive Bearer token rotation without relying on Google Play Services.
 */
@Keep
object ByocOAuthManager {

    private const val TAG = "ByocOAuthManager"
    private const val LOOPBACK_TIMEOUT_MS = 180_000 // 3 minutes timeout

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val refreshMutex = Mutex()

    /**
     * Generates a high-entropy cryptographic code verifier for PKCE (RFC 7636).
     */
    private fun generateCodeVerifier(): String {
        val secureRandom = SecureRandom()
        val code = ByteArray(64)
        secureRandom.nextBytes(code)
        return Base64.encodeToString(
            code,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    /**
     * Computes SHA-256 code challenge for PKCE.
     */
    private fun generateCodeChallenge(codeVerifier: String): String {
        val bytes = codeVerifier.toByteArray(Charsets.US_ASCII)
        val messageDigest = MessageDigest.getInstance("SHA-256")
        val digest = messageDigest.digest(bytes)
        return Base64.encodeToString(
            digest,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    /**
     * Generates a random cryptographic state string to prevent CSRF attacks.
     */
    private fun generateState(): String {
        val randomBytes = ByteArray(32)
        SecureRandom().nextBytes(randomBytes)
        return Base64.encodeToString(
            randomBytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    /**
     * Interactive Loopback Sign-In:
     * 1. Binds ephemeral ServerSocket on 127.0.0.1.
     * 2. Builds authorization URL with PKCE (S256) and offline access.
     * 3. Launches Custom Tabs / Browser.
     * 4. Listens for callback on loopback server with a 3-minute timeout.
     * 5. Exchanges authorization code for tokens and hydrates UserInfo.
     */
    suspend fun signInWithByoc(context: Context): Result<ByocSessionTokens> = withContext(Dispatchers.IO) {
        ByocConfigManager.ensureLoaded(context)
        val config = ByocConfigManager.configFlow.value
        if (!config.isEnabled || config.clientId.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("BYOC OAuth is not configured. Please import your client_secret_*.json file first.")
            )
        }

        val codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier)
        val state = generateState()

        // Bind ephemeral loopback server on localhost (127.0.0.1)
        val serverSocket = try {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).apply {
                soTimeout = LOOPBACK_TIMEOUT_MS
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind loopback ServerSocket: ${e.message}", e)
            return@withContext Result.failure(
                IllegalStateException("Could not bind local loopback server: ${e.localizedMessage}")
            )
        }

        val port = serverSocket.localPort
        val redirectUri = "http://127.0.0.1:$port"
        Log.i(TAG, "Starting BYOC OAuth loopback server on $redirectUri")

        try {
            // Build Google OAuth 2.0 Authorization URL
            val authUrl = Uri.parse(config.authUri).buildUpon()
                .appendQueryParameter("client_id", config.clientId)
                .appendQueryParameter("redirect_uri", redirectUri)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("scope", "https://www.googleapis.com/auth/drive openid email profile")
                .appendQueryParameter("access_type", "offline")
                .appendQueryParameter("prompt", "consent select_account")
                .appendQueryParameter("state", state)
                .appendQueryParameter("code_challenge", codeChallenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .build()
                .toString()

            // Launch browser / Custom Tab on Main thread
            withContext(Dispatchers.Main) {
                val launchedInCustomTabs = runCatching {
                    val customTabsIntent = CustomTabsIntent.Builder()
                        .setShowTitle(true)
                        .build()
                    customTabsIntent.launchUrl(context, Uri.parse(authUrl))
                }.isSuccess

                if (!launchedInCustomTabs) {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(browserIntent)
                }
            }

            // Accept connection with loopback handling (filtering favicon/pre-fetches)
            var authCode: String? = null
            var authError: String? = null
            val startTime = System.currentTimeMillis()

            while (authCode == null && authError == null) {
                val remainingTime = LOOPBACK_TIMEOUT_MS - (System.currentTimeMillis() - startTime)
                if (remainingTime <= 0) {
                    throw SocketTimeoutException("Authentication timed out after 3 minutes. Please try again.")
                }
                serverSocket.soTimeout = remainingTime.toInt().coerceAtLeast(1000)

                val clientSocket = serverSocket.accept()
                clientSocket.use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                    val requestLine = reader.readLine().orEmpty()
                    Log.d(TAG, "Loopback received HTTP request: $requestLine")

                    val parts = requestLine.split(" ")
                    val path = if (parts.size >= 2) parts[1] else "/"

                    // Ignore favicon or non-callback requests
                    if (path.startsWith("/favicon.ico")) {
                        val notFoundResponse = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        socket.getOutputStream().write(notFoundResponse.toByteArray(Charsets.UTF_8))
                        socket.getOutputStream().flush()
                        return@use
                    }

                    val callbackUri = Uri.parse("http://127.0.0.1$path")
                    val incomingState = callbackUri.getQueryParameter("state")
                    val incomingCode = callbackUri.getQueryParameter("code")
                    val errorParam = callbackUri.getQueryParameter("error")
                    val errorDesc = callbackUri.getQueryParameter("error_description")

                    if (!errorParam.isNullOrBlank()) {
                        authError = if (errorParam.equals("access_denied", ignoreCase = true)) {
                            "Access was denied by Google. Please ensure your Google account is added under Google Cloud Console → Google Auth Platform → Audience → Test users."
                        } else {
                            "Google authorization error: $errorParam (${errorDesc ?: "check OAuth client config"})"
                        }
                        sendLoopbackHtmlResponse(socket, isSuccess = false, title = "Sign-In Failed", message = authError!!)
                        return@use
                    }

                    if (incomingState != state) {
                        authError = "State parameter mismatch (CSRF protection). Please restart the sign-in process."
                        sendLoopbackHtmlResponse(socket, isSuccess = false, title = "Sign-In Error", message = authError!!)
                        return@use
                    }

                    if (!incomingCode.isNullOrBlank()) {
                        authCode = incomingCode
                        sendLoopbackHtmlResponse(
                            socket,
                            isSuccess = true,
                            title = "Connected to Clotube!",
                            message = "Your Google Drive account was successfully linked. You can close this tab and return to Clotube."
                        )
                        return@use
                    }

                    // Unknown request
                    val badReq = "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().write(badReq.toByteArray(Charsets.UTF_8))
                    socket.getOutputStream().flush()
                }
            }

            if (authError != null) {
                return@withContext Result.failure(IllegalStateException(authError))
            }

            val finalCode = authCode ?: return@withContext Result.failure(
                IllegalStateException("No authorization code received from Google OAuth callback.")
            )

            // Step 2: Exchange Authorization Code for Session Tokens
            exchangeCodeForTokens(context, config, finalCode, codeVerifier, redirectUri)
        } catch (e: SocketTimeoutException) {
            Log.w(TAG, "OAuth loopback timed out: ${e.message}")
            Result.failure(IllegalStateException("Authentication timed out. Please complete the browser sign-in within 3 minutes."))
        } catch (e: Exception) {
            Log.e(TAG, "BYOC sign-in failed: ${e.message}", e)
            Result.failure(e)
        } finally {
            try {
                serverSocket.close()
            } catch (_: Exception) {}
        }
    }

    /**
     * Renders a clean Obsidian Cinema dark confirmation or error web page in the browser tab.
     */
    private fun sendLoopbackHtmlResponse(
        socket: java.net.Socket,
        isSuccess: Boolean,
        title: String,
        message: String
    ) {
        try {
            val titleColor = if (isSuccess) "#38BDF8" else "#F87171"
            val badgeText = if (isSuccess) "✓ SUCCESS" else "✕ ERROR"
            val badgeBg = if (isSuccess) "rgba(56, 189, 248, 0.15)" else "rgba(248, 113, 113, 0.15)"
            val badgeColor = if (isSuccess) "#38BDF8" else "#F87171"

            val html = """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="utf-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <title>$title</title>
                    <style>
                        * { box-sizing: border-box; }
                        body {
                            background-color: #07090E;
                            color: #FFFFFF;
                            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;
                            display: flex;
                            align-items: center;
                            justify-content: center;
                            min-height: 100vh;
                            margin: 0;
                            padding: 20px;
                        }
                        .card {
                            background: #111622;
                            border: 1px solid #1E293B;
                            border-radius: 18px;
                            padding: 36px 28px;
                            max-width: 400px;
                            width: 100%;
                            text-align: center;
                            box-shadow: 0 16px 40px rgba(0, 0, 0, 0.6);
                        }
                        .badge {
                            display: inline-block;
                            padding: 5px 12px;
                            border-radius: 9999px;
                            font-size: 11px;
                            font-weight: 700;
                            letter-spacing: 0.05em;
                            background: $badgeBg;
                            color: $badgeColor;
                            margin-bottom: 16px;
                        }
                        h1 {
                            font-size: 22px;
                            font-weight: 700;
                            margin: 0 0 10px 0;
                            color: $titleColor;
                        }
                        p {
                            font-size: 14px;
                            color: #94A3B8;
                            line-height: 1.55;
                            margin: 0;
                        }
                    </style>
                </head>
                <body>
                    <div class="card">
                        <div class="badge">$badgeText</div>
                        <h1>$title</h1>
                        <p>$message</p>
                    </div>
                </body>
                </html>
            """.trimIndent()

            val bytes = html.toByteArray(Charsets.UTF_8)
            val responseHeader = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/html; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\n" +
                    "Connection: close\r\n\r\n"

            val output = socket.getOutputStream()
            output.write(responseHeader.toByteArray(Charsets.UTF_8))
            output.write(bytes)
            output.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to write HTML response to loopback client: ${e.message}")
        }
    }

    /**
     * Exchanges the authorization code for access and refresh tokens, queries Google userinfo,
     * and persists the session.
     */
    private suspend fun exchangeCodeForTokens(
        context: Context,
        config: ByocOAuthConfig,
        code: String,
        codeVerifier: String,
        redirectUri: String
    ): Result<ByocSessionTokens> = withContext(Dispatchers.IO) {
        val formBuilder = FormBody.Builder()
            .add("code", code)
            .add("client_id", config.clientId)
            .add("redirect_uri", redirectUri)
            .add("grant_type", "authorization_code")
            .add("code_verifier", codeVerifier)

        if (config.clientSecret.isNotBlank()) {
            formBuilder.add("client_secret", config.clientSecret)
        }

        val tokenRequest = Request.Builder()
            .url(config.tokenUri)
            .post(formBuilder.build())
            .build()

        try {
            httpClient.newCall(tokenRequest).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.e(TAG, "Code exchange failed: HTTP ${response.code} - $body")
                    val errorMsg = try {
                        val errorJson = JsonParser.parseString(body).asJsonObject
                        errorJson.get("error_description")?.asString
                            ?: errorJson.get("error")?.asString
                            ?: "Code exchange failed (HTTP ${response.code})"
                    } catch (_: Exception) {
                        "Code exchange failed (HTTP ${response.code})"
                    }
                    return@withContext Result.failure(IllegalStateException(errorMsg))
                }

                val json = JsonParser.parseString(body).asJsonObject
                val accessToken = json.get("access_token")?.asString.orEmpty()
                val refreshToken = json.get("refresh_token")?.asString.orEmpty()
                val expiresIn = json.get("expires_in")?.asLong ?: 3600L
                val expiresAtMs = System.currentTimeMillis() + (expiresIn * 1000L)

                if (accessToken.isBlank()) {
                    return@withContext Result.failure(
                        IllegalStateException("Google token response did not contain an access_token.")
                    )
                }

                // Query user info for profile hydration
                val userInfo = fetchUserInfo(accessToken)

                val session = ByocSessionTokens(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    expiresAtMs = expiresAtMs,
                    userEmail = userInfo.first,
                    userName = userInfo.second,
                    userAvatarUrl = userInfo.third
                )

                ByocConfigManager.saveSession(context, session)
                Log.i(TAG, "Successfully authenticated BYOC session for user ${session.userEmail}")
                Result.success(session)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Token exchange failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Queries Google UserInfo API (v3) using the newly acquired access token.
     * Returns Triple(email, name, avatarUrl).
     */
    private fun fetchUserInfo(accessToken: String): Triple<String, String, String> {
        val request = Request.Builder()
            .url("https://www.googleapis.com/oauth2/v3/userinfo")
            .header("Authorization", "Bearer $accessToken")
            .get()
            .build()

        return try {
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    val json = JsonParser.parseString(body).asJsonObject
                    val email = json.get("email")?.asString.orEmpty()
                    val name = json.get("name")?.asString.orEmpty()
                    val picture = json.get("picture")?.asString.orEmpty()
                    Triple(email, name, picture)
                } else {
                    Log.w(TAG, "UserInfo query returned HTTP ${response.code}")
                    Triple("", "", "")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch userinfo: ${e.message}")
            Triple("", "", "")
        }
    }

    /**
     * Returns a valid BYOC OAuth access token, automatically refreshing it if expired
     * or within 5 minutes of expiration.
     */
    suspend fun getValidByocAccessToken(context: Context, forceRefresh: Boolean = false): String = withContext(Dispatchers.IO) {
        refreshMutex.withLock {
            val session = ByocConfigManager.getSession(context) ?: return@withLock ""
            val now = System.currentTimeMillis()

            // Reuse in-memory/disk access token if not force-refreshed and still valid for > 5 minutes
            if (!forceRefresh && session.accessToken.isNotBlank() && (now < session.expiresAtMs - 5 * 60_000L)) {
                return@withLock session.accessToken
            }

            if (session.refreshToken.isBlank()) {
                Log.w(TAG, "No refresh token stored in BYOC session. Returning existing access token.")
                return@withLock session.accessToken
            }

            val config = ByocConfigManager.configFlow.value
            if (!config.isEnabled || config.clientId.isBlank()) {
                return@withLock ""
            }

            val formBuilder = FormBody.Builder()
                .add("client_id", config.clientId)
                .add("grant_type", "refresh_token")
                .add("refresh_token", session.refreshToken)

            if (config.clientSecret.isNotBlank()) {
                formBuilder.add("client_secret", config.clientSecret)
            }

            val request = Request.Builder()
                .url(config.tokenUri)
                .post(formBuilder.build())
                .build()

            try {
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        Log.e(TAG, "Failed to refresh BYOC token: HTTP ${response.code} - $body")
                        if (body.contains("invalid_grant", ignoreCase = true)) {
                            Log.w(TAG, "BYOC refresh token is invalid or revoked (invalid_grant). Clearing session.")
                            ByocConfigManager.clearSession(context)
                        }
                        return@withLock ""
                    }

                    val json = JsonParser.parseString(body).asJsonObject
                    val newAccessToken = json.get("access_token")?.asString.orEmpty()
                    val expiresIn = json.get("expires_in")?.asLong ?: 3600L
                    val newRefreshToken = json.get("refresh_token")?.asString?.takeIf { it.isNotBlank() }

                    if (newAccessToken.isNotBlank()) {
                        val updatedSession = session.copy(
                            accessToken = newAccessToken,
                            refreshToken = newRefreshToken ?: session.refreshToken,
                            expiresAtMs = System.currentTimeMillis() + (expiresIn * 1000L)
                        )
                        ByocConfigManager.saveSession(context, updatedSession)
                        Log.i(TAG, "Successfully refreshed BYOC access token (expires in ${expiresIn}s)")
                        return@withLock newAccessToken
                    }
                    ""
                }
            } catch (e: Exception) {
                Log.e(TAG, "Exception during BYOC token refresh: ${e.message}", e)
                ""
            }
        }
    }
}
