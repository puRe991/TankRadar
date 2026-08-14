package de.tankradar.app.domain

import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

/** A price row as stored, reduced to what the check needs. */
data class PriceObservation(
    val id: Long,
    val stationId: String,
    val fuelType: String,
    val price: Double,
    val time: LocalDateTime,
)

/** Station details that end up in the evidence document. */
data class StationDetails(
    val id: String,
    val name: String,
    val brand: String?,
    val street: String?,
    val houseNumber: String?,
    val postCode: String?,
    val city: String?,
    val latitude: Double?,
    val longitude: Double?,
)

data class PriceChangeCase(
    val eventId: String,
    val time: LocalDateTime,
    val stationId: String,
    val stationName: String,
    val brand: String?,
    val address: String,
    val coordinates: String?,
    val fuelType: String,
    val previousPrice: Double,
    val price: Double,
) {
    val difference: Double get() = price - previousPrice
}

/**
 * Port of `compliance_report.detect_price_change_cases`.
 *
 * Flags actual price changes recorded after a cutoff hour. As in the Python
 * edition this is explicitly only a *Prüffall*: the timing alone proves nothing,
 * it just collects the evidence for someone to look at.
 */
object PriceChangeCases {

    const val UNKNOWN_STATION = "Unbekannte Tankstelle"
    const val NO_ADDRESS = "Keine Anschrift hinterlegt"

    fun detect(
        observations: List<PriceObservation>,
        stations: List<StationDetails>,
        cutoffHour: Int = 12,
        windowStart: LocalDateTime,
    ): List<PriceChangeCase> {
        if (observations.isEmpty()) return emptyList()

        val stationsById = stations.associateBy { it.id }
        val cutoff = LocalTime.of(cutoffHour, 0)
        val cases = mutableListOf<PriceChangeCase>()

        observations
            .groupBy { it.stationId to it.fuelType }
            .forEach { (_, group) ->
                val ordered = group.sortedWith(compareBy({ it.time }, { it.id }))
                for (index in 1 until ordered.size) {
                    val current = ordered[index]
                    val previous = ordered[index - 1]
                    if (current.price == previous.price) continue
                    if (current.time.isBefore(windowStart)) continue
                    if (!current.time.toLocalTime().isAfter(cutoff)) continue

                    val station = stationsById[current.stationId]
                    cases += PriceChangeCase(
                        eventId = String.format(Locale.ROOT, "TR-%08d", current.id),
                        time = current.time,
                        stationId = current.stationId,
                        stationName = station?.name ?: UNKNOWN_STATION,
                        brand = station?.brand,
                        address = formatAddress(station),
                        coordinates = formatCoordinates(station),
                        fuelType = current.fuelType,
                        previousPrice = previous.price,
                        price = current.price,
                    )
                }
            }

        return cases.sortedByDescending { it.time }
    }

    fun formatAddress(station: StationDetails?): String {
        if (station == null) return NO_ADDRESS
        val street = listOfNotNull(station.street, station.houseNumber)
            .map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        val city = listOfNotNull(station.postCode, station.city)
            .map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        val combined = listOf(street, city).filter { it.isNotEmpty() }.joinToString(", ")
        return combined.ifEmpty { NO_ADDRESS }
    }

    private fun formatCoordinates(station: StationDetails?): String? {
        val latitude = station?.latitude ?: return null
        val longitude = station.longitude ?: return null
        // Locale.ROOT keeps the decimal point: a German locale would render
        // "50,1234, 8,5678", which is unreadable in the evidence document.
        return String.format(Locale.ROOT, "%.6f, %.6f", latitude, longitude)
    }
}
