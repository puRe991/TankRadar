package de.tankradar.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.tankradar.app.data.remote.FuelType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "tankradar_settings")

data class TankRadarSettings(
    val postCode: String = DEFAULT_POST_CODE,
    val radiusKm: Int = DEFAULT_RADIUS_KM,
    val fuelType: FuelType = FuelType.DEFAULT,
    val autoUpdate: Boolean = true,
    val updateIntervalMinutes: Int = DEFAULT_INTERVAL_MINUTES,
    val historyRetentionDays: Int = DEFAULT_RETENTION_DAYS,
) {
    companion object {
        const val DEFAULT_POST_CODE = "35444"
        const val DEFAULT_RADIUS_KM = 10
        const val DEFAULT_INTERVAL_MINUTES = 30

        /**
         * WorkManager refuses periodic work below 15 minutes, and on a phone a
         * tighter interval mostly costs battery.
         */
        const val MIN_INTERVAL_MINUTES = 15
        const val MAX_INTERVAL_MINUTES = 24 * 60

        /**
         * Price history is what the forecast and the Prüffälle view are built on,
         * but it grows without bound; 120 days covers both and keeps the database
         * small enough for a phone.
         */
        const val DEFAULT_RETENTION_DAYS = 120
    }
}

class SettingsRepository(private val context: Context) {

    val settings: Flow<TankRadarSettings> = context.dataStore.data.map { prefs ->
        TankRadarSettings(
            postCode = prefs[KEY_POST_CODE] ?: TankRadarSettings.DEFAULT_POST_CODE,
            radiusKm = prefs[KEY_RADIUS] ?: TankRadarSettings.DEFAULT_RADIUS_KM,
            fuelType = FuelType.fromKey(prefs[KEY_FUEL_TYPE]),
            autoUpdate = prefs[KEY_AUTO_UPDATE] ?: true,
            updateIntervalMinutes = prefs[KEY_INTERVAL] ?: TankRadarSettings.DEFAULT_INTERVAL_MINUTES,
            historyRetentionDays = prefs[KEY_RETENTION] ?: TankRadarSettings.DEFAULT_RETENTION_DAYS,
        )
    }

    suspend fun setPostCode(postCode: String) = edit { it[KEY_POST_CODE] = postCode.trim() }

    suspend fun setRadiusKm(radiusKm: Int) = edit { it[KEY_RADIUS] = radiusKm.coerceIn(1, 50) }

    suspend fun setFuelType(fuelType: FuelType) = edit { it[KEY_FUEL_TYPE] = fuelType.key }

    suspend fun setAutoUpdate(enabled: Boolean) = edit { it[KEY_AUTO_UPDATE] = enabled }

    suspend fun setUpdateInterval(minutes: Int) = edit {
        it[KEY_INTERVAL] = minutes.coerceIn(
            TankRadarSettings.MIN_INTERVAL_MINUTES,
            TankRadarSettings.MAX_INTERVAL_MINUTES,
        )
    }

    suspend fun setHistoryRetentionDays(days: Int) = edit { it[KEY_RETENTION] = days.coerceIn(7, 730) }

    private suspend fun edit(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        context.dataStore.edit(block)
    }

    private companion object {
        val KEY_POST_CODE = stringPreferencesKey("post_code")
        val KEY_RADIUS = intPreferencesKey("radius_km")
        val KEY_FUEL_TYPE = stringPreferencesKey("fuel_type")
        val KEY_AUTO_UPDATE = booleanPreferencesKey("auto_update")
        val KEY_INTERVAL = intPreferencesKey("interval_minutes")
        val KEY_RETENTION = intPreferencesKey("retention_days")
    }
}
