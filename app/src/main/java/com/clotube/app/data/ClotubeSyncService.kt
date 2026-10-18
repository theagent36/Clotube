package com.clotube.app.data

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

class ClotubeSyncService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d("ClotubeSync", "App task removed from Recents. Triggering persistent WorkManager sync...")
        DriveSyncWorker.enqueue(applicationContext)
        stopSelf()
    }
}
