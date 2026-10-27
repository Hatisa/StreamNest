package com.streamnest.tv.work

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.streamnest.tv.StreamNestApp

/**
 * Keeps the channel cache warm and the health table fresh.
 *
 * Runs on a periodic schedule (default every 12 h, matching how often the
 * upstream iptv-org index is rebuilt) and on demand from the UI. Never throws:
 * a worker that crashes repeatedly gets throttled by WorkManager, and a failed
 * refresh must not be fatal because the on-disk cache is still perfectly usable.
 */
class RefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as StreamNestApp).container
        return try {
            val sources = container.settings.sources()
            val enabled = sources.filter { it.enabled }
            if (enabled.isEmpty()) return Result.success()

            val statuses = container.channels.refresh(sources)

            if (container.settings.healthCheck()) {
                val checkBudget = if (isStopped) 0 else HEALTH_TARGETS
                if (checkBudget > 0) {
                    container.health.check(container.channels.snapshot(), maxTargets = checkBudget)
                }
            }

            if (statuses.any { it.ok }) Result.success() else Result.retry()
        } catch (t: Throwable) {
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    private companion object {
        /**
         * Streaming the whole public index is thousands of channels; probing all
         * of them would take hours and hammer the origins. Rotating a slice per
         * run keeps the table useful without being abusive.
         */
        const val HEALTH_TARGETS = 150
    }
}
