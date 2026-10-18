package com.clotube.app.player

import android.net.Uri

/**
 * Utility for constructing raw binary streaming URIs for Google Drive files.
 */
object DriveMediaSourceFactory {
    const val USER_AGENT = "Clotube/1.0"
    private const val BASE_DRIVE_URL = "https://www.googleapis.com/drive/v3/files/"
    private const val MEDIA_QUERY_PARAMS = "?alt=media&supportsAllDrives=true&acknowledgeAbuse=true"

    fun buildDriveStreamUri(fileId: String): Uri {
        return Uri.parse("$BASE_DRIVE_URL$fileId$MEDIA_QUERY_PARAMS")
    }
}
