package de.tankradar.tagebuch.data

import java.time.LocalDate

enum class FuelType(val label: String) {
    E5("Super E5"),
    E10("Super E10"),
    DIESEL("Diesel"),
    LPG("Autogas (LPG)"),
    OTHER("Sonstiges");

    companion object {
        fun parse(value: String?): FuelType =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: E10
    }
}

/**
 * Ein Tankvorgang.
 *
 * Kilometer können entweder als Kilometerstand ([odometer]) oder als seit dem
 * letzten Tanken gefahrene Strecke ([tripKm]) erfasst werden; beides ist optional.
 */
data class RefuelEntry(
    val id: Long,
    val date: LocalDate,
    val liters: Double,
    val pricePerLiter: Double,
    val totalCost: Double,
    val fuelType: FuelType,
    val fullTank: Boolean = true,
    val odometer: Double? = null,
    val tripKm: Double? = null,
    val station: String = "",
    val note: String = "",
)

data class VehicleSettings(
    val name: String = "Mein Auto",
    val tankCapacity: Double = 50.0,
    val defaultFuel: FuelType = FuelType.E10,
)
