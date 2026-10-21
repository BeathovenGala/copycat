package com.copycat.app

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context

/** Delivers queued items once the laptop is reachable, even when the background service is off. */
class RetryJob : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        Sender.connectAndRetry(this) {
            jobFinished(params, false)
            if (Sender.hasPending(this)) schedule(this, RETRY_DELAY_MS)
        }
        return true
    }

    override fun onStopJob(params: JobParameters) = true

    companion object {
        private const val JOB_ID = 47820
        private const val RETRY_DELAY_MS = 5 * 60_000L

        /** Runs once the phone has a network and at least [delayMs] has passed. */
        fun schedule(context: Context, delayMs: Long = 0) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (delayMs == 0L && scheduler.getPendingJob(JOB_ID) != null) return
            scheduler.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, RetryJob::class.java))
                    // Not UNMETERED: as the laptop's hotspot the phone's only network is mobile data.
                    .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                    .setMinimumLatency(delayMs)
                    .build(),
            )
        }
    }
}
