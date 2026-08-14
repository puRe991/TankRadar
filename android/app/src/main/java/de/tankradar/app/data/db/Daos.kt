package de.tankradar.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface StationDao {

    @Upsert
    suspend fun upsertAll(stations: List<StationEntity>)

    @Query("SELECT * FROM stations ORDER BY name")
    fun observeAll(): Flow<List<StationEntity>>

    @Query("SELECT * FROM stations WHERE id = :stationId")
    suspend fun findById(stationId: String): StationEntity?

    @Query("SELECT * FROM stations WHERE id = :stationId")
    fun observeById(stationId: String): Flow<StationEntity?>

    @Query("UPDATE stations SET isFavorite = :favorite WHERE id = :stationId")
    suspend fun setFavorite(stationId: String, favorite: Boolean)

    /**
     * Refresh the details a scrape provides without clobbering [StationEntity.isFavorite],
     * which only exists on the device. A plain upsert of a scraped station would
     * reset the star on every background run.
     */
    @Query(
        """
        UPDATE stations
        SET name = :name, brand = :brand, street = :street, houseNumber = :houseNumber,
            postCode = :postCode, city = :city, latitude = :latitude, longitude = :longitude
        WHERE id = :id
        """
    )
    suspend fun updateDetails(
        id: String,
        name: String,
        brand: String?,
        street: String?,
        houseNumber: String?,
        postCode: String?,
        city: String?,
        latitude: Double?,
        longitude: Double?,
    )

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnoring(stations: List<StationEntity>)

    @Query("DELETE FROM stations WHERE id = :stationId")
    suspend fun delete(stationId: String)

    @Query("SELECT * FROM stations")
    suspend fun allForReport(): List<StationEntity>

    @Query("SELECT COUNT(*) FROM stations")
    suspend fun count(): Int
}

@Dao
interface PriceDao {

    /**
     * The unique (stationId, fuelType, timestamp) index turns a repeated scrape
     * into a no-op, so the scraper can simply insert everything it saw.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(prices: List<FuelPriceEntity>): List<Long>

    @Query(
        """
        SELECT s.id AS id, s.name AS name, s.brand AS brand, s.city AS city,
               s.street AS street, s.houseNumber AS houseNumber, s.postCode AS postCode,
               s.latitude AS latitude, s.longitude AS longitude, s.isFavorite AS isFavorite,
               latest.price AS price, latest.timestamp AS timestamp,
               (
                   SELECT p2.price FROM fuel_prices p2
                   WHERE p2.stationId = s.id AND p2.fuelType = :fuelType
                     AND p2.timestamp < latest.timestamp AND p2.price <> latest.price
                   ORDER BY p2.timestamp DESC LIMIT 1
               ) AS previousPrice
        FROM stations s
        LEFT JOIN (
            SELECT p.stationId, p.price, p.timestamp
            FROM fuel_prices p
            JOIN (
                SELECT stationId, MAX(timestamp) AS maxTs
                FROM fuel_prices WHERE fuelType = :fuelType GROUP BY stationId
            ) newest ON newest.stationId = p.stationId AND newest.maxTs = p.timestamp
            WHERE p.fuelType = :fuelType
            GROUP BY p.stationId
        ) latest ON latest.stationId = s.id
        """
    )
    fun observeStationsWithLatestPrice(fuelType: String): Flow<List<StationWithPrice>>

    @Query(
        """
        SELECT * FROM fuel_prices
        WHERE stationId = :stationId AND fuelType = :fuelType AND timestamp >= :since
        ORDER BY timestamp ASC
        """
    )
    fun observeHistory(stationId: String, fuelType: String, since: Long): Flow<List<FuelPriceEntity>>

    @Query(
        """
        SELECT * FROM fuel_prices
        WHERE fuelType = :fuelType AND timestamp >= :since
        ORDER BY timestamp ASC
        """
    )
    fun observeRecent(fuelType: String, since: Long): Flow<List<FuelPriceEntity>>

    @Query("SELECT * FROM fuel_prices WHERE timestamp >= :since ORDER BY stationId, fuelType, timestamp ASC")
    suspend fun pricesSince(since: Long): List<FuelPriceEntity>

    @Query("SELECT MAX(timestamp) FROM fuel_prices")
    fun observeLatestTimestamp(): Flow<Long?>

    @Query("DELETE FROM fuel_prices WHERE stationId = :stationId")
    suspend fun deleteForStation(stationId: String)

    @Query("DELETE FROM fuel_prices WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("SELECT COUNT(*) FROM fuel_prices")
    suspend fun count(): Int
}

@Dao
interface RefuelDao {

    @Insert
    suspend fun insert(entry: RefuelLogEntity): Long

    @Query("DELETE FROM refuel_logs WHERE id = :entryId")
    suspend fun delete(entryId: Long)

    @Query("SELECT * FROM refuel_logs ORDER BY timestamp DESC")
    fun observeAll(): Flow<List<RefuelLogEntity>>
}
