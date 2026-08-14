package de.tankradar.app.data

import android.content.Context
import android.util.Log
import de.tankradar.app.data.db.FuelPriceEntity
import de.tankradar.app.data.db.PriceDao
import de.tankradar.app.data.db.RefuelDao
import de.tankradar.app.data.db.RefuelLogEntity
import de.tankradar.app.data.db.StationDao
import de.tankradar.app.data.db.StationEntity
import de.tankradar.app.data.db.StationWithPrice
import de.tankradar.app.data.db.TankRadarDatabase
import de.tankradar.app.data.remote.AdacClient
import de.tankradar.app.data.remote.FuelType
import de.tankradar.app.domain.PriceChangeCase
import de.tankradar.app.domain.PriceChangeCases
import de.tankradar.app.domain.PriceObservation
import de.tankradar.app.domain.StationDetails
import kotlinx.coroutines.flow.Flow
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Outcome of one scrape run, used for the status line in the UI. */
sealed interface ScrapeOutcome {
    data class Success(val stationsSeen: Int, val pricesStored: Int) : ScrapeOutcome
    data class Failure(val message: String) : ScrapeOutcome
}

/**
 * Owns everything price-related: scraping, storage and the derived views.
 *
 * This replaces the Python `DatabaseManager` + `ADACScraper` pair. It is the only
 * place that talks to the ADAC endpoint, so the background worker and a manual
 * pull-to-refresh go through exactly the same path.
 */
class PriceRepository(
    private val stationDao: StationDao,
    private val priceDao: PriceDao,
    private val refuelDao: RefuelDao,
    private val client: AdacClient = AdacClient(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    fun observeStations(fuelType: FuelType): Flow<List<StationWithPrice>> =
        priceDao.observeStationsWithLatestPrice(fuelType.key)

    fun observeHistory(stationId: String, fuelType: FuelType, days: Int): Flow<List<FuelPriceEntity>> =
        priceDao.observeHistory(stationId, fuelType.key, sinceMillis(days.toLong()))

    fun observeRecent(fuelType: FuelType, hours: Long): Flow<List<FuelPriceEntity>> =
        priceDao.observeRecent(fuelType.key, System.currentTimeMillis() - hours * 3_600_000L)

    fun observeStation(stationId: String) = stationDao.observeById(stationId)

    fun observeLatestTimestamp(): Flow<Long?> = priceDao.observeLatestTimestamp()

    fun observeRefuelLogs(): Flow<List<RefuelLogEntity>> = refuelDao.observeAll()

    suspend fun setFavorite(stationId: String, favorite: Boolean) =
        stationDao.setFavorite(stationId, favorite)

    suspend fun deleteStation(stationId: String) {
        priceDao.deleteForStation(stationId)
        stationDao.delete(stationId)
    }

    suspend fun addRefuelEntry(entry: RefuelLogEntity): Long = refuelDao.insert(entry)

    suspend fun deleteRefuelEntry(entryId: Long) = refuelDao.delete(entryId)

    /**
     * Fetch all four fuel types for [postCode] and store what came back.
     *
     * One timestamp is used for the whole run so a single refresh forms one
     * coherent snapshot, exactly like `cloud_scraper.main`.
     */
    suspend fun refresh(postCode: String, radiusKm: Int): ScrapeOutcome {
        val timestamp = System.currentTimeMillis()
        val stations = mutableMapOf<String, StationEntity>()
        val prices = mutableListOf<FuelPriceEntity>()
        var failures = 0
        var lastError: String? = null

        FuelType.entries.forEach { fuelType ->
            try {
                client.fetchStations(postCode, fuelType, radiusKm).forEach { station ->
                    stations[station.id] = StationEntity(
                        id = station.id,
                        name = listOf(station.operator, station.city)
                            .filter { it.isNotBlank() }
                            .joinToString(" ")
                            .ifBlank { "Station ${station.id}" },
                        brand = station.operator.ifBlank { null },
                        street = station.street.ifBlank { null },
                        houseNumber = station.houseNumber.ifBlank { null },
                        postCode = station.postCode.ifBlank { null },
                        city = station.city.ifBlank { null },
                        latitude = station.latitude,
                        longitude = station.longitude,
                    )
                    prices += FuelPriceEntity(
                        stationId = station.id,
                        fuelType = fuelType.key,
                        price = station.price,
                        timestamp = timestamp,
                    )
                }
            } catch (error: IOException) {
                // One fuel type failing must not discard the others.
                failures++
                lastError = error.message
                Log.w(TAG, "Fetching ${fuelType.adacName} failed: ${error.message}")
            }
        }

        if (failures == FuelType.entries.size) {
            return ScrapeOutcome.Failure(lastError ?: "Keine Verbindung zum ADAC-Dienst")
        }

        // Insert new stations, then refresh details on all of them without
        // touching the favourite flag, which only exists on the device.
        stationDao.insertIgnoring(stations.values.toList())
        stations.values.forEach { station ->
            stationDao.updateDetails(
                id = station.id,
                name = station.name,
                brand = station.brand,
                street = station.street,
                houseNumber = station.houseNumber,
                postCode = station.postCode,
                city = station.city,
                latitude = station.latitude,
                longitude = station.longitude,
            )
        }

        val insertedIds = priceDao.insertAll(prices)
        val stored = insertedIds.count { it != -1L }
        return ScrapeOutcome.Success(stationsSeen = stations.size, pricesStored = stored)
    }

    /** Drop history older than the configured retention so the database stays small. */
    suspend fun pruneHistory(retentionDays: Int): Int =
        priceDao.deleteOlderThan(sinceMillis(retentionDays.toLong()))

    /**
     * Price changes after [cutoffHour] within the last [days] days.
     *
     * Loads a small buffer of extra history so the first change inside the window
     * still has a predecessor to compare against.
     */
    suspend fun priceChangeCases(cutoffHour: Int = 12, days: Long = 30): List<PriceChangeCase> {
        val windowStart = LocalDateTime.now(zone).minusDays(days)
        val observations = priceDao.pricesSince(sinceMillis(days + LOOKBACK_BUFFER_DAYS))
            .map { row ->
                PriceObservation(
                    id = row.id,
                    stationId = row.stationId,
                    fuelType = row.fuelType,
                    price = row.price,
                    time = row.timestamp.toLocalDateTime(),
                )
            }

        val stations = stationDao.findAllDetails()
        return PriceChangeCases.detect(observations, stations, cutoffHour, windowStart)
    }

    private fun sinceMillis(days: Long): Long = System.currentTimeMillis() - days * 86_400_000L

    private fun Long.toLocalDateTime(): LocalDateTime =
        Instant.ofEpochMilli(this).atZone(zone).toLocalDateTime()

    companion object {
        private const val TAG = "PriceRepository"
        private const val LOOKBACK_BUFFER_DAYS = 2L

        fun create(context: Context): PriceRepository {
            val db = TankRadarDatabase.get(context)
            return PriceRepository(db.stationDao(), db.priceDao(), db.refuelDao())
        }
    }
}

/** Convenience projection used by the Prüffälle export. */
private suspend fun StationDao.findAllDetails(): List<StationDetails> =
    allForReport().map { station ->
        StationDetails(
            id = station.id,
            name = station.name,
            brand = station.brand,
            street = station.street,
            houseNumber = station.houseNumber,
            postCode = station.postCode,
            city = station.city,
            latitude = station.latitude,
            longitude = station.longitude,
        )
    }
