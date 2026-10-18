package com.clotube.app.data.repository

import com.clotube.app.auth.GoogleAuthRepository
import com.clotube.app.data.scanner.DriveCatalogScanner
import com.clotube.app.model.Movie
import com.clotube.app.model.TvSeries
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.drive.Drive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * High-level repository for media discovery in Clotube.
 * Coordinates DriveCatalogScanner recursive BFS traversals and Google Drive API sessions.
 */
class VideoRepository(
    private val authRepository: GoogleAuthRepository? = null,
    private val injectedDriveService: Drive? = null
) {
    suspend fun getDriveService(): Drive? = withContext(Dispatchers.IO) {
        if (injectedDriveService != null) return@withContext injectedDriveService
        val token = authRepository?.getAccessToken()?.getOrNull() ?: return@withContext null
        Drive.Builder(
            NetHttpTransport(),
            GsonFactory.getDefaultInstance()
        ) { request ->
            request.headers.authorization = "Bearer $token"
        }.setApplicationName("Clotube/1.0").build()
    }

    suspend fun scanMovies(rootMoviesFolderId: String): List<Movie> = withContext(Dispatchers.IO) {
        val service = getDriveService() ?: return@withContext emptyList()
        DriveCatalogScanner.scanMoviesRecursively(service, rootMoviesFolderId)
    }

    suspend fun scanSeries(rootSeriesFolderId: String): List<TvSeries> = withContext(Dispatchers.IO) {
        val service = getDriveService() ?: return@withContext emptyList()
        DriveCatalogScanner.scanSeriesRecursively(service, rootSeriesFolderId)
    }

    suspend fun scanDriveMedia(rootSeriesFolderId: String): List<TvSeries> = scanSeries(rootSeriesFolderId)

    suspend fun refreshSingleSeries(seriesFolderId: String, seriesTitleHint: String? = null): TvSeries? = withContext(Dispatchers.IO) {
        val service = getDriveService() ?: return@withContext null
        DriveCatalogScanner.refreshSingleSeries(service, seriesFolderId, seriesTitleHint)
    }

    suspend fun runDiagnosticTest(rootMoviesFolderId: String, rootSeriesFolderId: String? = null) = withContext(Dispatchers.IO) {
        val service = getDriveService() ?: return@withContext
        DriveCatalogScanner.runDiagnosticTest(service, rootMoviesFolderId, rootSeriesFolderId)
    }
}
