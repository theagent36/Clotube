package com.clotube.app.data

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ClotubeSyncService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d("ClotubeSync", "App task removed from Recents. Triggering persistent WorkManager sync and resuming downloads...")
        DriveSyncWorker.enqueue(applicationContext)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                DownloadRepository(applicationContext).resumeAllInterruptedDownloads()
            } catch (e: Exception) {
                Log.e("ClotubeSync", "Failed to resume downloads on task removed: ${e.message}", e)
            }
        }
        stopSelf()
    }
}
