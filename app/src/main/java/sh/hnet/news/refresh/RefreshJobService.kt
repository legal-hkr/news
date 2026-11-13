package sh.hnet.news.refresh

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.NetworkCapabilities
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import sh.hnet.news.NewsApplication
import sh.hnet.news.RefreshSettings

/**
 * Refresh in the background (SPEC §10.1, §14.5): one periodic job of the system's own scheduler,
 * with the network and charging conditions of the settings. The system picks the moment; nothing
 * is shown. Kept across restarts of the phone.
 */
class RefreshJobService : JobService() {
    private var waiting: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as NewsApplication
        val refresh = app.refresher.start(background = true) ?: app.refresher.current() ?: return false
        waiting = app.appScope.launch {
            refresh.join()
            jobFinished(params, false)
        }
        return true
    }

    /** The conditions are gone (the network changed, the time ran out): what was fetched stays, the rest waits for the next run. */
    override fun onStopJob(params: JobParameters): Boolean {
        waiting?.cancel()
        (application as NewsApplication).refresher.current()?.cancel()
        return false
    }

    companion object {
        private const val JOB_ID = 1

        /** Brings the scheduled job in line with the settings; leaves a job that already matches alone, so its period isn't restarted. */
        fun schedule(context: Context, settings: RefreshSettings) {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            if (settings.intervalHours == 0) {
                scheduler.cancel(JOB_ID)
                return
            }
            val period = settings.intervalHours * 60 * 60 * 1000L
            val network = if (settings.unmeteredOnly) JobInfo.NETWORK_TYPE_UNMETERED else JobInfo.NETWORK_TYPE_ANY
            val pending = scheduler.getPendingJob(JOB_ID)
            val unmetered = pending?.requiredNetwork?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
            if (pending != null && pending.intervalMillis == period && unmetered == settings.unmeteredOnly && pending.isRequireCharging == settings.chargingOnly) return
            scheduler.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, RefreshJobService::class.java))
                    .setPeriodic(period)
                    .setRequiredNetworkType(network)
                    .setRequiresCharging(settings.chargingOnly)
                    .setPersisted(true)
                    .build(),
            )
        }
    }
}
