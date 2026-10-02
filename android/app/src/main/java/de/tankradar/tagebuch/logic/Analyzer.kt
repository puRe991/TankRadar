package de.tankradar.tagebuch.logic

import de.tankradar.tagebuch.data.RefuelEntry
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/** Ein Verbrauchsabschnitt zwischen zwei Volltankungen. */
data class ConsumptionSegment(val endDate: LocalDate, val liters: Double, val km: Double) {
    val per100km: Double get() = liters / km * 100.0
}

data class MonthSummary(val month: YearMonth, val liters: Double, val cost: Double, val km: Double?)

enum class Confidence(val label: String) {
    NONE("Noch keine Prognose"),
    ROUGH("Grobe Schätzung"),
    OK("Ordentliche Schätzung"),
    GOOD("Gute Prognose"),
    VERY_GOOD("Sehr genaue Prognose"),
}

enum class DrivingTrend { THRIFTIER, NORMAL, HIGHER }

data class Analysis(
    val entryCount: Int,
    val totalLiters: Double,
    val totalCost: Double,
    val totalKm: Double?,
    val avgPricePerLiter: Double?,
    val minPrice: Double?,
    val maxPrice: Double?,
    val lastPrice: Double?,
    val trackedDays: Long,

    /** Gewichteter Durchschnitt aller Verbrauchsabschnitte (L/100 km). */
    val avgConsumption: Double?,
    /** Verbrauch der letzten Abschnitte, stärker gewichtet (L/100 km). */
    val recentConsumption: Double?,
    val consumptionSegments: List<ConsumptionSegment>,
    val drivingTrend: DrivingTrend?,
    val trendPercent: Double?,

    val litersPerMonth: Double?,
    val costPerMonth: Double?,
    val costPerMonthAtCurrentPrice: Double?,
    val kmPerMonth: Double?,
    val costPer100Km: Double?,

    /** Durchschnittlicher Abstand zwischen zwei Tankvorgängen (Tage, jüngere stärker gewichtet). */
    val avgDaysBetweenRefuels: Double?,
    /** Wie viele Tage eine volle Tankfüllung beim aktuellen Fahrverhalten reicht. */
    val daysPerFullTank: Double?,
    /** Reichweite einer vollen Tankfüllung in km. */
    val rangePerFullTank: Double?,
    val nextRefuelDate: LocalDate?,

    val months: List<MonthSummary>,
    val confidence: Confidence,
    /** Wie viele Einträge noch fehlen, bis die nächste Prognosestufe erreicht ist. */
    val entriesToNextLevel: Int?,
)

/**
 * Wertet das Tagebuch aus. Je mehr Einträge vorhanden sind, desto mehr Kennzahlen
 * lassen sich berechnen und desto stärker wird das jüngste Verhalten gewichtet,
 * sodass sich die Prognosen an Änderungen (Sommer/Winter, neuer Arbeitsweg …) anpassen.
 */
object Analyzer {

    /** Nach dieser Zeit zählt ein Intervall nur noch halb so viel. */
    private const val HALF_LIFE_DAYS = 120.0
    private const val DAYS_PER_MONTH = 30.4375

    fun analyze(input: List<RefuelEntry>, tankCapacity: Double, today: LocalDate = LocalDate.now()): Analysis {
        val entries = input.sortedWith(compareBy<RefuelEntry> { it.date }.thenBy { it.odometer ?: 0.0 }.thenBy { it.id })
        val n = entries.size

        val totalLiters = entries.sumOf { it.liters }
        val totalCost = entries.sumOf { it.totalCost }
        val avgPrice = if (totalLiters > 0) totalCost / totalLiters else null
        val trackedDays = if (n >= 2) ChronoUnit.DAYS.between(entries.first().date, entries.last().date) else 0L

        val distances = distancesSincePrevious(entries)
        val knownKm = distances.drop(1).filterNotNull()
        val totalKm = knownKm.takeIf { it.isNotEmpty() }?.sum()

        // --- Verbrauch nach der Volltank-Methode ---
        val segments = consumptionSegments(entries, distances)
        val avgConsumption = segments.takeIf { it.isNotEmpty() }
            ?.let { s -> s.sumOf { it.liters } / s.sumOf { it.km } * 100.0 }
        val recentConsumption = segments.takeIf { it.isNotEmpty() }?.let { s ->
            val recent = s.takeLast(3)
            recent.sumOf { it.liters } / recent.sumOf { it.km } * 100.0
        }
        var trend: DrivingTrend? = null
        var trendPercent: Double? = null
        if (segments.size >= 4 && avgConsumption != null && recentConsumption != null) {
            val pct = (recentConsumption - avgConsumption) / avgConsumption * 100.0
            trendPercent = pct
            trend = when {
                pct <= -4.0 -> DrivingTrend.THRIFTIER
                pct >= 4.0 -> DrivingTrend.HIGHER
                else -> DrivingTrend.NORMAL
            }
        }

        // --- Raten pro Tag, zeitgewichtet ---
        // Jedes Intervall i (zwischen Eintrag i-1 und i) steht für den Verbrauch,
        // der beim Tanken i wieder aufgefüllt wurde.
        val lastDate = entries.lastOrNull()?.date
        val gaps = (1 until n).map { ChronoUnit.DAYS.between(entries[it - 1].date, entries[it].date).toDouble() }
        val maxGap = maxGapDays(gaps)
        var wDays = 0.0; var wLiters = 0.0; var wCost = 0.0
        var wKmDays = 0.0; var wKm = 0.0
        var wGap = 0.0; var wGapSum = 0.0
        for (i in 1 until n) {
            val days = gaps[i - 1]
            // Sehr lange Lücken (Einträge vergessen) würden die Hochrechnung verfälschen.
            if (days > maxGap) continue
            val age = ChronoUnit.DAYS.between(entries[i].date, lastDate).toDouble()
            val w = exp(-ln(2.0) * age / HALF_LIFE_DAYS)
            // Mehrere Tankvorgänge am selben Tag zählen als ein halber Tag Abstand.
            val effDays = days.coerceAtLeast(0.5)
            wDays += w * effDays
            wLiters += w * entries[i].liters
            wCost += w * entries[i].totalCost
            wGap += w
            wGapSum += w * effDays
            distances[i]?.let { km ->
                wKmDays += w * effDays
                wKm += w * km
            }
        }
        val hasRates = n >= 2 && trackedDays > 0 && wDays > 0
        val litersPerDay = if (hasRates) wLiters / wDays else null
        val costPerDay = if (hasRates) wCost / wDays else null
        val kmPerDay = if (hasRates && wKmDays > 0) wKm / wKmDays else null

        val litersPerMonth = litersPerDay?.times(DAYS_PER_MONTH)
        val costPerMonth = costPerDay?.times(DAYS_PER_MONTH)
        val lastPrice = entries.lastOrNull()?.pricePerLiter
        val costAtCurrent = if (litersPerMonth != null && lastPrice != null) litersPerMonth * lastPrice else null

        // km/Monat: bevorzugt direkt gemessen, sonst über den Verbrauch geschätzt.
        val consumptionForEstimates = recentConsumption ?: avgConsumption
        val kmPerMonth = kmPerDay?.times(DAYS_PER_MONTH)
            ?: if (litersPerMonth != null && consumptionForEstimates != null) litersPerMonth / consumptionForEstimates * 100.0 else null

        val avgGap = if (hasRates && wGap > 0) wGapSum / wGap else null
        val daysPerFullTank = litersPerDay?.takeIf { it > 0 }?.let { tankCapacity / it }
        val range = consumptionForEstimates?.let { tankCapacity / it * 100.0 }
        val nextRefuel = if (avgGap != null && lastDate != null) {
            lastDate.plusDays(avgGap.roundToInt().toLong()).let { if (it.isBefore(today)) today else it }
        } else null

        val costPer100 = if (consumptionForEstimates != null && avgPrice != null) consumptionForEstimates * avgPrice else null

        // --- Monatsübersicht ---
        val months = entries.indices.groupBy { YearMonth.from(entries[it].date) }
            .map { (month, idx) ->
                val km = idx.mapNotNull { if (it == 0) null else distances[it] }
                MonthSummary(
                    month = month,
                    liters = idx.sumOf { entries[it].liters },
                    cost = idx.sumOf { entries[it].totalCost },
                    km = km.takeIf { it.isNotEmpty() }?.sum(),
                )
            }
            .sortedBy { it.month }

        // --- Prognosequalität ---
        val levels = listOf(2 to Confidence.ROUGH, 4 to Confidence.OK, 7 to Confidence.GOOD, 12 to Confidence.VERY_GOOD)
        val confidence = levels.lastOrNull { n >= it.first }?.second ?: Confidence.NONE
        val nextLevel = levels.firstOrNull { n < it.first }?.first
        val toNext = nextLevel?.minus(n)

        return Analysis(
            entryCount = n,
            totalLiters = totalLiters,
            totalCost = totalCost,
            totalKm = totalKm,
            avgPricePerLiter = avgPrice,
            minPrice = entries.minOfOrNull { it.pricePerLiter },
            maxPrice = entries.maxOfOrNull { it.pricePerLiter },
            lastPrice = lastPrice,
            trackedDays = trackedDays,
            avgConsumption = avgConsumption,
            recentConsumption = recentConsumption,
            consumptionSegments = segments,
            drivingTrend = trend,
            trendPercent = trendPercent,
            litersPerMonth = litersPerMonth,
            costPerMonth = costPerMonth,
            costPerMonthAtCurrentPrice = costAtCurrent,
            kmPerMonth = kmPerMonth,
            costPer100Km = costPer100,
            avgDaysBetweenRefuels = avgGap,
            daysPerFullTank = daysPerFullTank,
            rangePerFullTank = range,
            nextRefuelDate = nextRefuel,
            months = months,
            confidence = confidence,
            entriesToNextLevel = toNext,
        )
    }

    /**
     * Ab dieser Länge gilt ein Abstand zwischen zwei Tankvorgängen als Lücke im
     * Tagebuch: mindestens 60 Tage bzw. das Vierfache des typischen Abstands.
     */
    fun maxGapDays(gaps: List<Double>): Double {
        if (gaps.size < 3) return Double.MAX_VALUE
        val sorted = gaps.sorted()
        val median = sorted[sorted.size / 2]
        return maxOf(60.0, median * 4)
    }

    /**
     * Gefahrene Kilometer seit dem vorherigen Eintrag: entweder direkt eingetragen
     * oder aus der Differenz zweier Kilometerstände.
     */
    fun distancesSincePrevious(entries: List<RefuelEntry>): List<Double?> =
        entries.mapIndexed { i, e ->
            e.tripKm?.takeIf { it > 0 } ?: run {
                val prevOdo = entries.getOrNull(i - 1)?.odometer
                val odo = e.odometer
                if (prevOdo != null && odo != null && odo > prevOdo) odo - prevOdo else null
            }
        }

    /**
     * Volltank-Methode: Zwischen zwei Volltankungen entspricht die nachgetankte
     * Menge (inkl. Teilbetankungen dazwischen) genau dem Verbrauch auf der Strecke.
     */
    fun consumptionSegments(entries: List<RefuelEntry>, distances: List<Double?>): List<ConsumptionSegment> {
        val result = mutableListOf<ConsumptionSegment>()
        var lastFull = entries.indexOfFirst { it.fullTank }
        if (lastFull < 0) return result
        var liters = 0.0
        var km = 0.0
        var valid = true
        for (i in lastFull + 1 until entries.size) {
            liters += entries[i].liters
            val d = distances[i]
            if (d == null) valid = false else km += d
            if (entries[i].fullTank) {
                val per100 = if (km > 0) liters / km * 100.0 else 0.0
                // Unplausible Werte (Tippfehler) nicht in die Statistik übernehmen.
                if (valid && km >= 20 && per100 in 1.0..40.0) {
                    result += ConsumptionSegment(entries[i].date, liters, km)
                }
                lastFull = i
                liters = 0.0; km = 0.0; valid = true
            }
        }
        return result
    }
}
