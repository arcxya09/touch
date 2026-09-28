package com.arcxya09.touch.data

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import com.arcxya09.touch.TouchApp
import kotlinx.coroutines.*

/** Offline maintenance. Android can defer jobs; access checks enforce expiry even between jobs. */
class LocalCleanupJob : JobService() {
    private var work: Job? = null
    override fun onStartJob(params: JobParameters): Boolean {
        work = CoroutineScope(Dispatchers.IO).launch {
            val success = runCatching { (application as TouchApp).repository.purge() }.isSuccess
            jobFinished(params, !success)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { work?.cancel(); return true }
    companion object {
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (scheduler.getPendingJob(4021) == null) scheduler.schedule(
                JobInfo.Builder(4021, ComponentName(context, LocalCleanupJob::class.java))
                    .setPeriodic(15 * 60 * 1000L).setPersisted(true).build())
        }
    }
}
