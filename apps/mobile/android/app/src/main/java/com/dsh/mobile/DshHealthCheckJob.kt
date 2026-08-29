package com.dsh.mobile

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Periodic health check: checks proot process every 60 seconds.
 * Auto-restarts container if process died.
 */
class DshHealthCheckJob : JobService() {

    companion object {
        private const val TAG = "DshHealthCheck"
        private const val JOB_ID = 1001

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JOB_SCHEDULER_SERVICE) as JobScheduler
            val jobInfo = JobInfo.Builder(JOB_ID,
                ComponentName(context, DshHealthCheckJob::class.java)).apply {
                setPeriodic(60_000L)
                setPersisted(true)
                setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
            }.build()
            scheduler.schedule(jobInfo)
            Log.i(TAG, "Health check scheduled: every 60s")
        }
    }

    override fun onStartJob(params: JobParameters): Boolean {
        Thread {
            try {
                val pid = DshKeepAliveBridge.getMonitoredPid()
                if (pid > 0) {
                    val process = Runtime.getRuntime().exec("kill -0 $pid")
                    val exitCode = process.waitFor()
                    if (exitCode != 0) {
                        Log.w(TAG, "Container process $pid died, restarting...")
                        val intent = Intent(this, DshKeepAliveService::class.java).apply {
                            action = "com.dsh.mobile.RESTART"
                        }
                        startService(intent)
                    } else {
                        Log.d(TAG, "Container process $pid is alive")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Health check failed", e)
            }
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true
}
