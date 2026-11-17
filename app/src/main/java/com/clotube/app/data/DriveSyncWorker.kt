package com.clotube.app.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Backward-compatible delegator for [com.clotube.app.sync.DriveSyncWorker].
 * Preserves legacy component references without breaking binary or source contracts.
 */
class DriveSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    private val delegate = com.clotube.app.sync.DriveSyncWorker(appContext, workerParams)

    override suspend fun doWork(): Result {
        return delegate.doWork()
    }

    companion object {
        const val KEY_ACCOUNT_NAME = com.clotube.app.sync.DriveSyncWorker.KEY_ACCOUNT_NAME

        fun enqueue(context: Context) {
            com.clotube.app.sync.DriveSyncWorker.enqueueImmediateSync(context)
        }

        fun enqueueImmediateSync(context: Context) {
            com.clotube.app.sync.DriveSyncWorker.enqueueImmediateSync(context)
        }
    }
}
