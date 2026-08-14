package de.tankradar.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room schema. Mirrors the tables of the Python edition (`database.py`) so a
 * price history exported from a desktop installation stays importable.
 */

@Entity(tableName = "stations")
data class StationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val brand: String? = null,
    val street: String? = null,
    val houseNumber: String? = null,
    val postCode: String? = null,
    val city: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val isFavorite: Boolean = false,
)

@Entity(
    tableName = "fuel_prices",
    indices = [
        Index(value = ["stationId", "timestamp"]),
        // The scraper writes the same (station, fuel, timestamp) triple whenever a
        // run overlaps a previous one; a unique index makes the insert idempotent
        // instead of requiring a read-before-write per row.
        Index(value = ["stationId", "fuelType", "timestamp"], unique = true),
    ],
)
data class FuelPriceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stationId: String,
    val fuelType: String,
    val price: Double,
    /** Epoch milliseconds. */
    val timestamp: Long,
)

@Entity(tableName = "refuel_logs")
data class RefuelLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val stationId: String? = null,
    val stationNameFallback: String? = null,
    val timestamp: Long,
    val fuelType: String,
    val liters: Double,
    val pricePerLiter: Double,
    val totalCost: Double,
    val odometer: Int? = null,
    val notes: String? = null,
)

/** A station together with its most recent price for one fuel type. */
data class StationWithPrice(
    val id: String,
    val name: String,
    val brand: String?,
    val city: String?,
    val street: String?,
    val houseNumber: String?,
    val postCode: String?,
    val latitude: Double?,
    val longitude: Double?,
    val isFavorite: Boolean,
    val price: Double?,
    val timestamp: Long?,
    val previousPrice: Double?,
)
