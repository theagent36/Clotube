package com.clotube.app.data

import android.util.Log
import okhttp3.Call
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object LocalStreamProxy {
    private const val TAG = "LocalStreamProxy"
    const val PORT = 8998
    private const val CHUNK_BUFFER_SIZE = 64 * 1024

    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private val isRunning = AtomicBoolean(false)

    @Volatile private var tokenProvider: (suspend () -> String)? = null
    @Volatile private var cachedToken: String = ""
    @Volatile private var tokenLastFetchedMs: Long = 0L

    // Disable automatic redirects so we can manually follow 302s while preserving Authorization & Range headers
    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(16, 2, TimeUnit.MINUTES))
        .dispatcher(Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 20
        })
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    fun initTokenProvider(provider: suspend () -> String) {
        this.tokenProvider = provider
    }

    fun invalidateTokenCache() {
        cachedToken = ""
        tokenLastFetchedMs = 0L
    }

    private suspend fun getFreshToken(forceRefresh: Boolean = false): String {
        val now = System.currentTimeMillis()
        if (!forceRefresh && cachedToken.isNotBlank() && (now - tokenLastFetchedMs < 50 * 60 * 1000L)) {
            return cachedToken
        }
        val provider = tokenProvider ?: return ""
        val token = try {
            provider().trim()
        } catch (e: Exception) {
            Log.e(TAG, "Token provider threw exception: ${e.message}")
            ""
        }
        if (token.isNotBlank()) {
            cachedToken = token
            tokenLastFetchedMs = now
        }
        return token
    }

    @Synchronized
    fun start() {
        if (isRunning.get() && serverSocket?.isBound == true && !serverSocket!!.isClosed) {
            Log.d(TAG, "LocalStreamProxy already running on 127.0.0.1:$PORT")
            return
        }

        try {
            serverSocket?.close()
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT), 50)
            }
            isRunning.set(true)
            Log.i(TAG, "LocalStreamProxy bound to 127.0.0.1:$PORT")

            executor.submit {
                while (isRunning.get() && serverSocket?.isClosed == false) {
                    try {
                        val clientSocket = serverSocket?.accept() ?: break
                        executor.submit { handleClientSocket(clientSocket) }
                    } catch (e: Exception) {
                        if (!isRunning.get()) break
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start LocalStreamProxy: ${e.message}", e)
        }
    }

    private fun executeDriveRequestWithRedirects(
        initialUrl: String,
        token: String,
        rangeHeader: String?,
        onCallCreated: (Call) -> Unit
    ): Response {
        var currentUrl = initialUrl
        var redirectCount = 0

        while (redirectCount < 5) {
            val reqBuilder = Request.Builder()
                .url(currentUrl)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("User-Agent", "ClotubeProxy/1.0")

            if (!rangeHeader.isNullOrBlank()) {
                reqBuilder.addHeader("Range", rangeHeader)
            }

            val call = okHttpClient.newCall(reqBuilder.build())
            onCallCreated(call)
            val response = call.execute()

            if (response.code in listOf(301, 302, 303, 307, 308)) {
                val location = response.header("Location")
                response.close()
                if (location.isNullOrBlank()) {
                    return response
                }
                currentUrl = location
                redirectCount++
                continue
            }

            return response
        }

        throw SocketException("Too many redirects for $initialUrl")
    }

    private fun handleClientSocket(clientSocket: Socket) {
        var activeCall: Call? = null
        try {
            clientSocket.soTimeout = 25000
            clientSocket.tcpNoDelay = true
            val reader = BufferedReader(InputStreamReader(clientSocket.getInputStream()))
            val firstLine = reader.readLine() ?: return

            val parts = firstLine.split(" ")
            if (parts.size < 2) return

            val pathWithParams = parts[1]
            val fileId = pathWithParams.substringAfter("fileId=", "").substringBefore("&")
            if (fileId.isBlank()) return

            var rangeHeader: String? = null
            var line: String? = reader.readLine()
            while (!line.isNullOrBlank()) {
                if (line.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = line.substringAfter(":").trim()
                }
                line = reader.readLine()
            }

            var token = kotlinx.coroutines.runBlocking { getFreshToken(forceRefresh = false) }
            val driveUrl = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media&supportsAllDrives=true&acknowledgeAbuse=true"

            Log.d(TAG, "Incoming GET for fileId=$fileId (Range: $rangeHeader, tokenPresent=${token.isNotBlank()})")

            var driveResponse = executeDriveRequestWithRedirects(
                initialUrl = driveUrl,
                token = token,
                rangeHeader = rangeHeader,
                onCallCreated = { activeCall = it }
            )

            // If 401 or 403, force-refresh token once and retry immediately
            if (driveResponse.code == 401 || driveResponse.code == 403) {
                Log.w(TAG, "Drive returned ${driveResponse.code} for $fileId. Evicting cached token and retrying with fresh token...")
                driveResponse.close()
                invalidateTokenCache()
                token = kotlinx.coroutines.runBlocking { getFreshToken(forceRefresh = true) }

                driveResponse = executeDriveRequestWithRedirects(
                    initialUrl = driveUrl,
                    token = token,
                    rangeHeader = rangeHeader,
                    onCallCreated = { activeCall = it }
                )
            }

            driveResponse.use { response ->
                val output = BufferedOutputStream(clientSocket.getOutputStream(), CHUNK_BUFFER_SIZE)

                Log.d(TAG, "Drive returned code=${response.code} for fileId=$fileId (Range: $rangeHeader)")

                if (response.code == 416) {
                    Log.w(TAG, "EOF Range (416) reached for $fileId; sending empty EOF response")
                    output.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    output.flush()
                    return@use
                }

                // Never pipe a 401/403 JSON body to LibVLC as fake video bytes
                if (response.code >= 400) {
                    val errText = response.body?.string() ?: ""
                    Log.e(TAG, "Drive error ${response.code} for $fileId: $errText")
                    output.write("HTTP/1.1 ${response.code} Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                    output.flush()
                    return@use
                }

                val statusLine = when (response.code) {
                    206 -> "HTTP/1.1 206 Partial Content\r\n"
                    200 -> "HTTP/1.1 200 OK\r\n"
                    else -> "HTTP/1.1 ${response.code} OK\r\n"
                }
                output.write(statusLine.toByteArray())

                val forwardHeaders = listOf("Content-Type", "Content-Length", "Content-Range", "Accept-Ranges")
                for (headerName in forwardHeaders) {
                    response.header(headerName)?.let { value ->
                        output.write("$headerName: $value\r\n".toByteArray())
                    }
                }
                output.write("Connection: close\r\n\r\n".toByteArray())
                output.flush()

                val responseBody = response.body
                if (responseBody != null) {
                    val buffer = ByteArray(CHUNK_BUFFER_SIZE)
                    val inputStream = responseBody.byteStream()
                    var bytesRead: Int
                    var totalStreamed = 0L

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        output.flush()
                        totalStreamed += bytesRead
                    }
                    Log.d(TAG, "Finished streaming $totalStreamed bytes for fileId=$fileId")
                }
            }
        } catch (e: SocketException) {
            Log.d(TAG, "Client closed socket on seek (${e.message})")
        } catch (e: Exception) {
            if (e.message?.contains("CANCEL", ignoreCase = true) == true ||
                e.message?.contains("Broken pipe", ignoreCase = true) == true
            ) {
                Log.d(TAG, "Stream reset/canceled on seek (${e.message})")
            } else {
                Log.e(TAG, "Proxy error: ${e.message}")
            }
        } finally {
            activeCall?.cancel()
            try { clientSocket.close() } catch (ignored: Exception) {}
        }
    }

    @Synchronized
    fun stop() {
        isRunning.set(false)
        try { serverSocket?.close() } catch (ignored: Exception) {}
    }
}
