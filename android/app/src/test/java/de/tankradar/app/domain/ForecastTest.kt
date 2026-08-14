package de.tankradar.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * The forecast is the part of TankRadar that tells someone when to drive to a
 * station, so the ported model is checked against patterns with a known answer.
 */
class ForecastTest {

    private val start: LocalDateTime = LocalDateTime.of(2026, 3, 2, 0, 0)

    @Test
    fun `no forecast below the minimum number of points`() {
        val history = (0 until Forecast.MIN_DATA_POINTS - 1).map {
            PricePoint(start.plusHours(it.toLong()), 1.75)
        }

        assertNull(Forecast.predictNext24h(history))
    }

    @Test
    fun `implausible prices are discarded before counting points`() {
        // Enough rows, but all outside the plausible range, so nothing is usable.
        val history = (0 until 40).map { PricePoint(start.plusHours(it.toLong()), 42.0) }

        assertNull(Forecast.predictNext24h(history))
    }

    @Test
    fun `a station that is cheap every evening is predicted cheapest in the evening`() {
        // Fourteen days of a clean daily cycle: cheapest at 19:00, dearest at 07:00.
        val history = buildList {
            repeat(14) { day ->
                repeat(24) { hour ->
                    val price = when (hour) {
                        19, 20 -> 1.70
                        7, 8 -> 1.86
                        else -> 1.79
                    }
                    add(PricePoint(start.plusDays(day.toLong()).plusHours(hour.toLong()), price))
                }
            }
        }

        val result = Forecast.predictNext24h(history)

        assertNotNull(result)
        assertTrue(
            "cheapest hour was ${result!!.bestTime.hour}",
            result.bestTime.hour in listOf(19, 20),
        )
    }

    @Test
    fun `the forecast covers the next 24 hours starting one hour after the last point`() {
        val history = (0 until 48).map { PricePoint(start.plusHours(it.toLong()), 1.70 + it % 5 * 0.01) }

        val result = Forecast.predictNext24h(history)!!

        assertEquals(24, result.points.size)
        assertEquals(start.plusHours(47).plusHours(1), result.points.first().time)
        assertEquals(start.plusHours(47).plusHours(24), result.points.last().time)
    }

    @Test
    fun `predictions stay inside the plausible price band`() {
        val history = (0 until 60).map {
            PricePoint(start.plusHours(it.toLong()), if (it % 2 == 0) 0.6 else 4.9)
        }

        val result = Forecast.predictNext24h(history)!!

        result.points.forEach { point ->
            assertTrue("price ${point.price} out of band", point.price in 1.0..3.0)
            assertTrue(point.lower <= point.price)
            assertTrue(point.upper >= point.price)
        }
    }

    @Test
    fun `the best price matches the cheapest forecast point`() {
        val history = (0 until 72).map {
            PricePoint(start.plusHours(it.toLong()), 1.75 + (it % 24) * 0.002)
        }

        val result = Forecast.predictNext24h(history)!!

        assertEquals(result.points.minOf { it.price }, result.bestPrice, 1e-9)
    }

    @Test
    fun `median handles even and odd sizes`() {
        assertEquals(2.0, Forecast.median(listOf(1.0, 2.0, 3.0)), 1e-9)
        assertEquals(2.5, Forecast.median(listOf(1.0, 2.0, 3.0, 4.0)), 1e-9)
    }

    @Test
    fun `quantile interpolates like pandas`() {
        // pandas: Series([1,2,3,4]).quantile(0.75) == 3.25
        assertEquals(3.25, Forecast.quantile(listOf(1.0, 2.0, 3.0, 4.0), 0.75)!!, 1e-9)
    }
}
