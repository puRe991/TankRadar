package de.tankradar.app.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.tankradar.app.data.PriceRepository
import de.tankradar.app.data.ScrapeOutcome
import de.tankradar.app.data.SettingsRepository
import de.tankradar.app.data.TankRadarSettings
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Periodic price update.
 *
 * This is what replaces the APScheduler job of the desktop edition. WorkManager
 * survives reboots and app kills, which a foreground timer would not, and it
 * defers the run when the device has no network instead of burning a retry.
 */
class ScrapeWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val settings = SettingsRepository(applicationContext).settings.first()
        val repository = PriceRepository.create(applicationContext)

        return when (val outcome = repository.refresh(settings.postCode, settings.radiusKm)) {
            is ScrapeOutcome.Success -> {
                repository.pruneHistory(settings.historyRetentionDays)
                Log.i(TAG, "Stored ${outcome.pricesStored} prices for ${outcome.stationsSeen} stations")
                Result.success()
            }
            is ScrapeOutcome.Failure -> {
                Log.w(TAG, "Scrape failed: ${outcome.message}")
                // Transient by nature (no signal, ADAC hiccup); let WorkManager
                // back off and try again rather than dropping the schedule.
                Result.retry()
            }
        }
    }

    companion object {
        private const val TAG = "ScrapeWorker"
        private const val WORK_NAME = "tankradar-price-update"

        fun schedule(context: Context, settings: TankRadarSettings) {
            val manager = WorkManager.getInstance(context)
            if (!settings.autoUpdate) {
                manager.cancelUniqueWork(WORK_NAME)
                return
            }

            val request = PeriodicWorkRequestBuilder<ScrapeWorker>(
                settings.updateIntervalMinutes.toLong().coerceAtLeast(
                    TankRadarSettings.MIN_INTERVAL_MINUTES.toLong()
                ),
                TimeUnit.MINUTES,
            )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()

            // UPDATE keeps the existing schedule's history but picks up a changed
            // interval, so toggling the setting does not restart the countdown.
            manager.enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
