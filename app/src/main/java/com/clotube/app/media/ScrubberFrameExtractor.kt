package com.clotube.app.media

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * High-performance, debounced video frame extractor leveraging MediaMetadataRetriever
 * and an in-memory LruCache for rapid scrubber thumbnail preview generation.
 */
class ScrubberFrameExtractor(private val context: Context) {
    // Cache up to 30 thumbnails in memory (~6MB)
    private val bitmapCache = object : LruCache<Long, Bitmap>(30) {}
    private var retriever: MediaMetadataRetriever? = null
    private var currentDataSourceUri: String? = null

    suspend fun prepare(mediaUri: String, headers: Map<String, String> = emptyMap()) = withContext(Dispatchers.IO) {
        if (currentDataSourceUri != mediaUri) {
            release()
            try {
                val newRetriever = MediaMetadataRetriever()
                if (mediaUri.startsWith("content://") || mediaUri.startsWith("file://")) {
                    newRetriever.setDataSource(context, Uri.parse(mediaUri))
                } else if (mediaUri.startsWith("http://") || mediaUri.startsWith("https://")) {
                    if (headers.isNotEmpty()) {
                        newRetriever.setDataSource(mediaUri, headers)
                    } else {
                        newRetriever.setDataSource(mediaUri, HashMap<String, String>())
                    }
                } else {
                    newRetriever.setDataSource(mediaUri)
                }
                retriever = newRetriever
                currentDataSourceUri = mediaUri
            } catch (e: Exception) {
                android.util.Log.e("ScrubberFrameExtractor", "Failed to set data source for $mediaUri", e)
                retriever = null
                currentDataSourceUri = null
            }
        }
    }

    suspend fun getFrameAt(timeMs: Long): Bitmap? = withContext(Dispatchers.IO) {
        // Round to nearest 5-second interval for cache reusability
        val cacheKey = (timeMs / 5000L) * 5000L
        bitmapCache.get(cacheKey)?.let { return@withContext it }

        val activeRetriever = retriever ?: return@withContext null
        return@withContext try {
            val timeUs = timeMs * 1000L
            // Use OPTION_CLOSEST_SYNC to read keyframes instantly without decoding full GOP sequences
            val frame = activeRetriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (frame != null) {
                // Scale down to thumbnail size to minimize memory footprint
                val scaled = Bitmap.createScaledBitmap(frame, 240, 135, true)
                bitmapCache.put(cacheKey, scaled)
                scaled
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun getDurationMs(): Long {
        return try {
            retriever?.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (ignored: Exception) {
            0L
        }
    }

    fun release() {
        try {
            retriever?.release()
        } catch (ignored: Exception) {}
        retriever = null
        currentDataSourceUri = null
        bitmapCache.evictAll()
    }
}
