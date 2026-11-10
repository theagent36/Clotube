package com.clotube.app.media

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

object DriveStreamEngine {
    private const val TAG = "DriveStreamEngine"
    const val PORT = 8998

    private var serverSocket: ServerSocket? = null
    private val isRunning = AtomicBoolean(false)
    private val executor = Executors.newCachedThreadPool()

    @Volatile
    private var tokenProvider: (suspend (forceRefresh: Boolean) -> String?)? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun initialize(provider: suspend (forceRefresh: Boolean) -> String?) {
        this.tokenProvider = provider
        startServer()
    }

    @Synchronized
    fun startServer() {
        if (isRunning.get() && serverSocket != null && !serverSocket!!.isClosed) return
        try {
            // Strictly bind IPv4 loopback (127.0.0.1)
            serverSocket = ServerSocket(PORT, 100, InetAddress.getByName("127.0.0.1")).apply {
                reuseAddress = true
            }
            isRunning.set(true)
            Log.i(TAG, "DriveStreamEngine active on 127.0.0.1:$PORT")

            executor.execute {
                while (isRunning.get()) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        executor.execute { handleClient(client) }
                    } catch (e: Exception) {
                        if (isRunning.get()) Log.w(TAG, "Socket accept notice: ${e.message}")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind DriveStreamEngine on port $PORT: ${e.message}", e)
        }
    }

    fun getStreamUrl(fileId: String): String {
        startServer()
        return "http://127.0.0.1:$PORT/stream?fileId=$fileId"
    }

    private fun handleClient(client: Socket) {
        Log.i(TAG, "--> ACCEPTED CONNECTION from ${client.inetAddress}:${client.port}")
        var upstreamResponse: Response? = null
        try {
            client.soTimeout = 30000
            val input = client.getInputStream()
            val output = client.getOutputStream()
            val reader = BufferedReader(InputStreamReader(input))

            val requestLine = reader.readLine() ?: return
            val requestParts = requestLine.split(" ")
            if (requestParts.size < 2) return
            val method = requestParts[0].uppercase()
            val path = requestParts[1]

            val fileId = path.substringAfter("fileId=", "").substringBefore("&")
            if (fileId.isBlank()) {
                output.write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray())
                output.flush()
                return
            }

            var rangeHeader: String? = null
            var line: String?
            while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                if (line!!.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = line!!.substringAfter(":").trim()
                }
            }

            Log.i(TAG, "--> LibVLC request: $method $fileId | Range:$rangeHeader")

            var token = kotlinx.coroutines.runBlocking { tokenProvider?.invoke(false) } ?: ""

            val driveUrl = "https://www.googleapis.com/drive/v3/files/$fileId?alt=media&supportsAllDrives=true&acknowledgeAbuse=true"
            val reqBuilder = Request.Builder()
                .url(driveUrl)
                .header("User-Agent", "ClotubeEngine/2.0")
                .header("Authorization", "Bearer $token")

            if (method == "HEAD") reqBuilder.head()
            if (!rangeHeader.isNullOrBlank()) reqBuilder.header("Range", rangeHeader)

            upstreamResponse = httpClient.newCall(reqBuilder.build()).execute()

            if (upstreamResponse.code == 401) {
                upstreamResponse.close()
                Log.w(TAG, "Received 401 from Drive. Refreshing token...")
                token = kotlinx.coroutines.runBlocking { tokenProvider?.invoke(true) } ?: ""
                val retryReq = Request.Builder()
                    .url(driveUrl)
                    .header("User-Agent", "ClotubeEngine/2.0")
                    .header("Authorization", "Bearer $token")
                    .apply {
                        if (method == "HEAD") head()
                        if (!rangeHeader.isNullOrBlank()) header("Range", rangeHeader)
                    }
                    .build()
                upstreamResponse = httpClient.newCall(retryReq).execute()
            }

            val code = upstreamResponse.code
            val message = upstreamResponse.message.ifBlank { "OK" }
            val contentType = upstreamResponse.header("Content-Type") ?: "video/x-matroska"
            val contentLength = upstreamResponse.header("Content-Length")
            val contentRange = upstreamResponse.header("Content-Range")
            val acceptRanges = upstreamResponse.header("Accept-Ranges") ?: "bytes"

            Log.i(TAG, "<-- Drive response: $code $message | Range: $contentRange | Length:$contentLength")

            val headerBuilder = StringBuilder()
            headerBuilder.append("HTTP/1.1 $code $message\r\n")
            headerBuilder.append("Content-Type: $contentType\r\n")
            headerBuilder.append("Accept-Ranges: $acceptRanges\r\n")
            if (!contentLength.isNullOrBlank()) headerBuilder.append("Content-Length: $contentLength\r\n")
            if (!contentRange.isNullOrBlank()) headerBuilder.append("Content-Range: $contentRange\r\n")
            headerBuilder.append("Connection: close\r\n\r\n")

            output.write(headerBuilder.toString().toByteArray())
            output.flush()

            if (method == "HEAD" || code >= 400) return

            val body = upstreamResponse.body
            if (body != null) {
                val byteStream = body.byteStream()
                val buffer = ByteArray(64 * 1024)
                var bytesSent = 0L
                var read: Int
                while (byteStream.read(buffer).also { read = it } != -1) {
                    try {
                        output.write(buffer, 0, read)
                        output.flush()
                        bytesSent += read
                    } catch (se: SocketException) {
                        break
                    }
                }
                Log.d(TAG, "Finished transfer for $fileId: $bytesSent bytes sent.")
            }
        } catch (_: SocketException) {
        } catch (e: Exception) {
            Log.w(TAG, "Stream handler exception: ${e.message}")
        } finally {
            try { upstreamResponse?.close() } catch (_: Throwable) {}
            try { client.close() } catch (_: Throwable) {}
        }
    }
}
