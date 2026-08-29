package com.dsh.mobile

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * WorkManager fallback restart: auto-triggered when Service is killed.
 */
class DshRestartWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    companion object {
        private const val TAG = "DshRestartWorker"

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<DshRestartWorker>()
                    .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
                    .build()
            )
            Log.i(TAG, "Restart worker enqueued")
        }
    }

    override suspend fun doWork(): Result = try {
        Log.i(TAG, "Attempting container restart...")
        DshHealthCheckJob.schedule(applicationContext)
        Result.success()
    } catch (e: Exception) {
        Log.e(TAG, "Restart failed", e)
        Result.retry()
    }
}
