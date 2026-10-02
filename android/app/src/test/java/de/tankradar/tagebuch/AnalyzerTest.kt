package de.tankradar.tagebuch

import de.tankradar.tagebuch.data.Csv
import de.tankradar.tagebuch.data.FuelType
import de.tankradar.tagebuch.data.RefuelEntry
import de.tankradar.tagebuch.logic.Analyzer
import de.tankradar.tagebuch.logic.Confidence
import de.tankradar.tagebuch.logic.DrivingTrend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class AnalyzerTest {

    private val start = LocalDate.of(2026, 1, 1)

    private fun entry(id: Long, day: Long, liters: Double, price: Double = 1.80, odo: Double? = null, trip: Double? = null, full: Boolean = true) =
        RefuelEntry(id, start.plusDays(day), liters, price, liters * price, FuelType.E10, full, odo, trip)

    @Test
    fun emptyDiaryHasNoPrognosis() {
        val a = Analyzer.analyze(emptyList(), 50.0, start)
        assertEquals(Confidence.NONE, a.confidence)
        assertNull(a.costPerMonth)
        assertNull(a.avgConsumption)
    }

    @Test
    fun steadyDriverGivesExactRates() {
        // Alle 10 Tage 40 L für 600 km -> 6,67 L/100 km, 4 L/Tag
        val entries = (0..6).map { entry(it.toLong() + 1, it * 10L, 40.0, odo = 10_000.0 + it * 600) }
        val a = Analyzer.analyze(entries, 50.0, start.plusDays(60))
        assertEquals(40.0 / 600 * 100, a.avgConsumption!!, 1e-9)
        assertEquals(4.0 * 30.4375, a.litersPerMonth!!, 1e-6)
        assertEquals(4.0 * 1.80 * 30.4375, a.costPerMonth!!, 1e-6)
        assertEquals(60.0 * 30.4375, a.kmPerMonth!!, 1e-6)
        assertEquals(12.5, a.daysPerFullTank!!, 1e-6)
        assertEquals(750.0, a.rangePerFullTank!!, 1e-6)
        assertEquals(10.0, a.avgDaysBetweenRefuels!!, 1e-9)
        assertEquals(start.plusDays(70), a.nextRefuelDate)
        assertEquals(DrivingTrend.NORMAL, a.drivingTrend)
        assertEquals(Confidence.GOOD, a.confidence)
    }

    @Test
    fun partialFillsAreAddedToNextFullSegment() {
        val entries = listOf(
            entry(1, 0, 45.0, odo = 1000.0),
            entry(2, 5, 20.0, odo = 1300.0, full = false),
            entry(3, 10, 25.0, odo = 1700.0),
        )
        val segs = Analyzer.consumptionSegments(entries, Analyzer.distancesSincePrevious(entries))
        assertEquals(1, segs.size)
        assertEquals(45.0 / 700 * 100, segs[0].per100km, 1e-9)
    }

    @Test
    fun tripKmWorksWithoutOdometer() {
        val entries = listOf(entry(1, 0, 40.0), entry(2, 7, 30.0, trip = 500.0), entry(3, 14, 33.0, trip = 550.0))
        val a = Analyzer.analyze(entries, 50.0, start.plusDays(14))
        assertEquals(63.0 / 1050 * 100, a.avgConsumption!!, 1e-9)
        assertEquals(1050.0, a.totalKm!!, 1e-9)
    }

    @Test
    fun implausibleSegmentIsIgnored() {
        val entries = listOf(entry(1, 0, 40.0, odo = 1000.0), entry(2, 7, 40.0, odo = 1010.0), entry(3, 14, 40.0, odo = 1610.0))
        val a = Analyzer.analyze(entries, 50.0, start.plusDays(14))
        assertEquals(1, a.consumptionSegments.size)
    }

    @Test
    fun recentBehaviourWeighsMore() {
        // Früher 30 L alle 10 Tage, zuletzt 30 L alle 5 Tage -> Prognose näher an 6 L/Tag als am Mittel.
        val old = (0..5).map { entry(it.toLong() + 1, it * 10L, 30.0) }
        val recent = (1..6).map { entry(it.toLong() + 10, 50L + it * 5L, 30.0) }
        val a = Analyzer.analyze(old + recent, 50.0, start.plusDays(80))
        val litersPerDay = a.litersPerMonth!! / 30.4375
        // Ungewichtet wären es 330 L / 80 Tage = 4,125 L/Tag.
        assertTrue("war $litersPerDay", litersPerDay > 4.125)
    }

    @Test
    fun longGapInDiaryIsIgnored() {
        // Alle 10 Tage 40 L, dann ein halbes Jahr nichts eingetragen.
        val before = (0..4).map { entry(it.toLong() + 1, it * 10L, 40.0) }
        val after = (0..4).map { entry(it.toLong() + 10, 220L + it * 10L, 40.0) }
        val a = Analyzer.analyze(before + after, 50.0, start.plusDays(260))
        assertEquals(4.0 * 30.4375, a.litersPerMonth!!, 1e-6)
        assertEquals(10.0, a.avgDaysBetweenRefuels!!, 1e-9)
    }

    @Test
    fun higherRecentConsumptionIsDetected() {
        val entries = (0..7).map { i ->
            val liters = if (i >= 5) 48.0 else 36.0
            entry(i.toLong() + 1, i * 10L, liters, odo = 10_000.0 + i * 600)
        }
        val a = Analyzer.analyze(entries, 50.0, start.plusDays(70))
        assertEquals(DrivingTrend.HIGHER, a.drivingTrend)
    }

    @Test
    fun csvRoundTrip() {
        val entries = listOf(entry(1, 0, 40.5, odo = 1234.0), entry(2, 9, 22.0, trip = 320.0, full = false).copy(station = "Aral; Bahnhof", note = "Urlaub"))
        val back = Csv.import(Csv.export(entries), 1)
        assertEquals(2, back.size)
        assertEquals(entries[0].copy(), back[0])
        assertEquals("Aral, Bahnhof", back[1].station)
        assertEquals(false, back[1].fullTank)
        assertNotNull(back[1].tripKm)
    }
}
