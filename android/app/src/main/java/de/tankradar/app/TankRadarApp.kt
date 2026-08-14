package de.tankradar.app

import android.app.Application
import de.tankradar.app.data.SettingsRepository
import de.tankradar.app.work.ScrapeWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

class TankRadarApp : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()

        // Keep the background schedule in sync with the settings: changing the
        // interval or switching auto-update off takes effect without a restart.
        SettingsRepository(this).settings
            .distinctUntilChangedBy { it.autoUpdate to it.updateIntervalMinutes }
            .onEach { settings -> ScrapeWorker.schedule(this, settings) }
            .launchIn(scope)
    }
}
