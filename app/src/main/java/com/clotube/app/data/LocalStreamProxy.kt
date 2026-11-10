package com.clotube.app.data

import com.clotube.app.media.DriveStreamEngine

object LocalStreamProxy {
    const val PORT = DriveStreamEngine.PORT

    fun start() {
        DriveStreamEngine.startServer()
    }

    fun initTokenProvider(provider: suspend () -> String) {
        DriveStreamEngine.initialize { provider() }
    }

    fun invalidateTokenCache() {
        // Handled reactively by DriveStreamEngine upon 401 response
    }
}
