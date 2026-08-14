package de.tankradar.app.domain

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** One observed price point. */
data class PricePoint(val time: LocalDateTime, val price: Double)

/** One predicted hour, with the band the model considers plausible. */
data class ForecastPoint(
    val time: LocalDateTime,
    val price: Double,
    val lower: Double,
    val upper: Double,
)

data class ForecastResult(
    val points: List<ForecastPoint>,
    val bestTime: LocalDateTime,
    val bestPrice: Double,
    val bestPriceLower: Double,
    val bestPriceUpper: Double,
    val modelName: String,
)

/**
 * Port of `prediction_model._predict_with_adaptive_daily_pattern`.
 *
 * Prophet cannot run on Android — it needs a C++ Stan toolchain — so the phone
 * uses the same recency-weighted daily-pattern model the Python edition already
 * falls back to on 32-bit Windows. It learns each hour's typical deviation from
 * that day's median price and applies that shape to the latest price level, so a
 * station that changes its rhythm is followed within about a week.
 */
object Forecast {

    /** Below this many usable points a forecast is guesswork, so none is offered. */
    const val MIN_DATA_POINTS = 10

    private const val HORIZON_HOURS = 24
    private const val PRICE_FLOOR = 1.0
    private const val PRICE_CAP = 3.0

    /** Half-life of the recency weighting, in hours (seven days). */
    private const val WEIGHT_HALF_LIFE_HOURS = 24.0 * 7.0

    const val MODEL_NAME = "Adaptives Tagesmuster"

    fun predictNext24h(history: List<PricePoint>): ForecastResult? {
        val prepared = history
            .filter { it.price in 0.5..5.0 }
            .sortedBy { it.time }
        if (prepared.size < MIN_DATA_POINTS) return null

        val now = prepared.last().time
        val currentPrice = prepared.last().price

        val dailyMedian = prepared.groupBy { it.time.toLocalDate() }
            .mapValues { (_, points) -> median(points.map { it.price }) }

        data class Sample(val weekday: Int, val hour: Int, val delta: Double, val weight: Double)

        val samples = prepared.map { point ->
            val ageHours = java.time.Duration.between(point.time, now).toMinutes() / 60.0
            Sample(
                weekday = point.time.dayOfWeek.value,
                hour = point.time.hour,
                delta = point.price - (dailyMedian[point.time.toLocalDate()] ?: point.price),
                weight = 0.5.pow(ageHours / WEIGHT_HALF_LIFE_HOURS),
            )
        }

        val globalDelta = weightedMean(samples.map { it.delta to it.weight }) ?: 0.0
        val hourDelta = samples.groupBy { it.hour }
            .mapValues { (_, group) -> weightedMean(group.map { it.delta to it.weight }) ?: globalDelta }
        val weekdayHourDelta = samples.groupBy { it.weekday to it.hour }
            .mapValues { (_, group) -> weightedMean(group.map { it.delta to it.weight }) ?: globalDelta }

        val recentWindowStart = now.minusHours(6)
        val recentPrices = prepared.filter { !it.time.isBefore(recentWindowStart) }.map { it.price }
        val level = (median(recentPrices).takeIf { recentPrices.isNotEmpty() } ?: currentPrice) * 0.7 +
            currentPrice * 0.3

        // How far the pattern typically misses, used as the confidence band.
        val residuals = samples.map { sample ->
            val pattern = weekdayHourDelta[sample.weekday to sample.hour]
                ?: hourDelta[sample.hour]
                ?: globalDelta
            kotlin.math.abs(sample.delta - pattern)
        }
        val uncertainty = quantile(residuals, 0.75)
            ?.let { max(0.02, min(0.12, it)) }
            ?: 0.03

        val points = (1..HORIZON_HOURS).map { offset ->
            val time = now.plusHours(offset.toLong())
            val hourly = hourDelta[time.hour] ?: globalDelta
            val combined = (weekdayHourDelta[time.dayOfWeek.value to time.hour] ?: hourly) * 0.65 +
                hourly * 0.35
            val price = (level + combined).coerceIn(PRICE_FLOOR, PRICE_CAP)
            ForecastPoint(
                time = time,
                price = price,
                lower = max(PRICE_FLOOR, price - uncertainty),
                upper = min(PRICE_CAP, price + uncertainty),
            )
        }

        val best = points.minBy { it.price }
        return ForecastResult(
            points = points,
            bestTime = best.time,
            bestPrice = best.price,
            bestPriceLower = best.lower,
            bestPriceUpper = best.upper,
            modelName = MODEL_NAME,
        )
    }

    fun toPricePoints(timestampsMillis: List<Long>, prices: List<Double>, zone: ZoneId = ZoneId.systemDefault()) =
        timestampsMillis.zip(prices) { millis, price ->
            PricePoint(
                time = java.time.Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime(),
                price = price,
            )
        }

    internal fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
    }

    /** Linear-interpolation quantile, matching pandas' default. */
    internal fun quantile(values: List<Double>, q: Double): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        if (sorted.size == 1) return sorted[0]
        val position = q * (sorted.size - 1)
        val lowerIndex = kotlin.math.floor(position).toInt()
        val upperIndex = kotlin.math.ceil(position).toInt()
        if (lowerIndex == upperIndex) return sorted[lowerIndex]
        val fraction = position - lowerIndex
        return sorted[lowerIndex] + (sorted[upperIndex] - sorted[lowerIndex]) * fraction
    }

    private fun weightedMean(pairs: List<Pair<Double, Double>>): Double? {
        val usable = pairs.filter { it.second > 0 }
        if (usable.isEmpty()) return null
        val weightSum = usable.sumOf { it.second }
        if (weightSum <= 0) return null
        return usable.sumOf { it.first * it.second } / weightSum
    }
}
