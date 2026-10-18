package com.clotube.app

import android.app.Application
import com.clotube.app.data.DriveWatchHistorySync

class ClotubeApp : Application() {

    companion object {
        lateinit var instance: ClotubeApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        DriveWatchHistorySync.init(this)
    }
}
