package de.tankradar.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * The Prüffall list is meant to be handed to an authority, so the rules that put
 * a row in it are pinned down here.
 */
class PriceChangeCasesTest {

    private val windowStart = LocalDateTime.of(2026, 3, 1, 0, 0)
    private val station = StationDetails(
        id = "s1",
        name = "ARAL Lahnau",
        brand = "ARAL",
        street = "Hauptstrasse",
        houseNumber = "5",
        postCode = "35633",
        city = "Lahnau",
        latitude = 50.5678,
        longitude = 8.1234,
    )

    private fun observation(id: Long, time: LocalDateTime, price: Double, fuel: String = "e10") =
        PriceObservation(id, "s1", fuel, price, time)

    @Test
    fun `an actual change after the cutoff becomes a case`() {
        val cases = PriceChangeCases.detect(
            observations = listOf(
                observation(1, LocalDateTime.of(2026, 3, 5, 11, 0), 1.759),
                observation(2, LocalDateTime.of(2026, 3, 5, 14, 30), 1.799),
            ),
            stations = listOf(station),
            cutoffHour = 12,
            windowStart = windowStart,
        )

        assertEquals(1, cases.size)
        assertEquals("TR-00000002", cases[0].eventId)
        assertEquals(1.759, cases[0].previousPrice, 1e-9)
        assertEquals(1.799, cases[0].price, 1e-9)
        assertEquals(0.04, cases[0].difference, 1e-9)
    }

    @Test
    fun `a change before the cutoff is not a case`() {
        val cases = PriceChangeCases.detect(
            observations = listOf(
                observation(1, LocalDateTime.of(2026, 3, 5, 8, 0), 1.759),
                observation(2, LocalDateTime.of(2026, 3, 5, 11, 30), 1.799),
            ),
            stations = listOf(station),
            windowStart = windowStart,
        )

        assertTrue(cases.isEmpty())
    }

    @Test
    fun `exactly at the cutoff is not after it`() {
        val cases = PriceChangeCases.detect(
            observations = listOf(
                observation(1, LocalDateTime.of(2026, 3, 5, 11, 0), 1.759),
                observation(2, LocalDateTime.of(2026, 3, 5, 12, 0), 1.799),
            ),
            stations = listOf(station),
            windowStart = windowStart,
        )

        assertTrue(cases.isEmpty())
    }

    @Test
    fun `an unchanged price is not a case even after the cutoff`() {
        val cases = PriceChangeCases.detect(
            observations = listOf(
                observation(1, LocalDateTime.of(2026, 3, 5, 13, 0), 1.759),
                observation(2, LocalDateTime.of(2026, 3, 5, 14, 0), 1.759),
            ),
            stations = listOf(station),
            windowStart = windowStart,
        )

        assertTrue(cases.isEmpty())
    }

    @Test
    fun `history before the window only provides the previous price`() {
        val cases = PriceChangeCases.detect(
            observations = listOf(
                // Outside the window: must not become a case on its own...
                observation(1, LocalDateTime.of(2026, 2, 20, 15, 0), 1.700),
                observation(2, LocalDateTime.of(2026, 2, 21, 15, 0), 1.750),
                // ...but must still be the predecessor of the first in-window row.
                observation(3, LocalDateTime.of(2026, 3, 2, 13, 0), 1.800),
            ),
            stations = listOf(station),
            windowStart = windowStart,
        )

        assertEquals(1, cases.size)
        assertEquals("TR-00000003", cases[0].eventId)
        assertEquals(1.750, cases[0].previousPrice, 1e-9)
    }

    @Test
    fun `fuel types are tracked separately`() {
        val cases = PriceChangeCases.detect(
            observations = listOf(
                observation(1, LocalDateTime.of(2026, 3, 5, 13, 0), 1.700, fuel = "e10"),
                observation(2, LocalDateTime.of(2026, 3, 5, 13, 1), 1.900, fuel = "diesel"),
                observation(3, LocalDateTime.of(2026, 3, 5, 14, 0), 1.750, fuel = "e10"),
            ),
            stations = listOf(station),
            windowStart = windowStart,
        )

        // Only e10 changed; diesel has a single observation and no predecessor.
        assertEquals(1, cases.size)
        assertEquals("e10", cases[0].fuelType)
        assertEquals(1.700, cases[0].previousPrice, 1e-9)
    }

    @Test
    fun `cases are ordered newest first`() {
        val cases = PriceChangeCases.detect(
            observations = listOf(
                observation(1, LocalDateTime.of(2026, 3, 5, 13, 0), 1.700),
                observation(2, LocalDateTime.of(2026, 3, 5, 14, 0), 1.750),
                observation(3, LocalDateTime.of(2026, 3, 6, 15, 0), 1.800),
            ),
            stations = listOf(station),
            windowStart = windowStart,
        )

        assertEquals(2, cases.size)
        assertTrue(cases[0].time.isAfter(cases[1].time))
    }

    @Test
    fun `station details are attached to the case`() {
        val cases = PriceChangeCases.detect(
            observations = listOf(
                observation(1, LocalDateTime.of(2026, 3, 5, 13, 0), 1.700),
                observation(2, LocalDateTime.of(2026, 3, 5, 14, 0), 1.750),
            ),
            stations = listOf(station),
            windowStart = windowStart,
        )

        assertEquals("ARAL Lahnau", cases[0].stationName)
        assertEquals("Hauptstrasse 5, 35633 Lahnau", cases[0].address)
        assertEquals("50.567800, 8.123400", cases[0].coordinates)
    }

    @Test
    fun `an unknown station still produces a usable case`() {
        val cases = PriceChangeCases.detect(
            observations = listOf(
                observation(1, LocalDateTime.of(2026, 3, 5, 13, 0), 1.700),
                observation(2, LocalDateTime.of(2026, 3, 5, 14, 0), 1.750),
            ),
            stations = emptyList(),
            windowStart = windowStart,
        )

        assertEquals(PriceChangeCases.UNKNOWN_STATION, cases[0].stationName)
        assertEquals(PriceChangeCases.NO_ADDRESS, cases[0].address)
        assertEquals(null, cases[0].coordinates)
    }
}
