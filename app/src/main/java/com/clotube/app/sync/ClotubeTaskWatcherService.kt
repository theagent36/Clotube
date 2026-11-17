package com.clotube.app.sync

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Lightweight background task watcher service registered with android:stopWithTask="false".
 *
 * When the user swipes Clotube from the Recent Apps list (swipe-to-kill), Android invokes
 * [onTaskRemoved]. This service captures the lifecycle event and enqueues a persistent
 * expedited WorkManager job ([DriveSyncWorker]) so that pending watch history and watchlist
 * mutations are never lost or dropped.
 */
class ClotubeTaskWatcherService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "ClotubeSync: App task removed from Recents. Triggering persistent WorkManager sync...")
        DriveSyncWorker.enqueueImmediateSync(applicationContext)
        stopSelf()
    }

    companion object {
        private const val TAG = "ClotubeSync"
    }
}
