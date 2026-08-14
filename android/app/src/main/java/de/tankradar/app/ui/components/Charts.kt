package de.tankradar.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import de.tankradar.app.ui.theme.TankRadarAccent
import de.tankradar.app.ui.theme.TankRadarDanger
import de.tankradar.app.ui.theme.TankRadarSuccess
import de.tankradar.app.ui.theme.TankRadarTextDim
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A point on the price chart; [isForecast] switches it to the dashed series. */
data class ChartPoint(
    val time: LocalDateTime,
    val price: Double,
    val isForecast: Boolean = false,
)

/**
 * Price history and forecast, drawn directly on a Compose canvas.
 *
 * A charting library would pull in a sizeable dependency for one screen, and the
 * chart only ever needs two line series plus a band, so it is drawn by hand.
 */
@Composable
fun PriceChart(
    history: List<ChartPoint>,
    forecast: List<ChartPoint>,
    bestTime: LocalDateTime?,
    bestPrice: Double?,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val all = history + forecast

    if (all.size < 2) {
        Box(modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
            Text("Noch zu wenig Verlauf für ein Diagramm", color = TankRadarTextDim)
        }
        return
    }

    val minPrice = all.minOf { it.price }
    val maxPrice = all.maxOf { it.price }
    // A flat series would collapse to a zero-height range and divide by zero.
    val padding = maxOf(0.02, (maxPrice - minPrice) * 0.15)
    val low = minPrice - padding
    val high = maxPrice + padding

    val start = all.minOf { it.time }
    val end = all.maxOf { it.time }
    val totalMinutes = java.time.Duration.between(start, end).toMinutes().coerceAtLeast(1L)

    Canvas(modifier.fillMaxWidth().height(220.dp)) {
        val leftAxis = 52.dp.toPx()
        val bottomAxis = 22.dp.toPx()
        val plotWidth = size.width - leftAxis
        val plotHeight = size.height - bottomAxis

        fun xOf(time: LocalDateTime): Float {
            val minutes = java.time.Duration.between(start, time).toMinutes().toFloat()
            return leftAxis + plotWidth * (minutes / totalMinutes)
        }

        fun yOf(price: Double): Float {
            val fraction = ((price - low) / (high - low)).toFloat()
            return plotHeight - plotHeight * fraction
        }

        drawGrid(measurer, leftAxis, plotWidth, plotHeight, low, high)
        drawSeries(history.map { Offset(xOf(it.time), yOf(it.price)) }, TankRadarAccent, dashed = false)
        drawSeries(forecast.map { Offset(xOf(it.time), yOf(it.price)) }, TankRadarSuccess, dashed = true)

        if (bestTime != null && bestPrice != null) {
            drawCircle(
                color = TankRadarSuccess,
                radius = 6.dp.toPx(),
                center = Offset(xOf(bestTime), yOf(bestPrice)),
            )
        }

        val timeFormat = DateTimeFormatter.ofPattern("dd.MM.", Locale.GERMANY)
        listOf(start to Alignment.Start, end to Alignment.End).forEach { (time, _) ->
            val label = time.format(timeFormat)
            val laidOut = measurer.measure(label, TextStyle(fontSize = 10.sp, color = TankRadarTextDim))
            val x = (xOf(time) - laidOut.size.width / 2f)
                .coerceIn(leftAxis, size.width - laidOut.size.width)
            drawText(laidOut, topLeft = Offset(x, plotHeight + 4.dp.toPx()))
        }
    }
}

private fun DrawScope.drawGrid(
    measurer: TextMeasurer,
    leftAxis: Float,
    plotWidth: Float,
    plotHeight: Float,
    low: Double,
    high: Double,
) {
    val steps = 4
    repeat(steps + 1) { index ->
        val fraction = index / steps.toFloat()
        val y = plotHeight - plotHeight * fraction
        drawLine(
            color = Color.White.copy(alpha = 0.08f),
            start = Offset(leftAxis, y),
            end = Offset(leftAxis + plotWidth, y),
            strokeWidth = 1f,
        )
        val price = low + (high - low) * fraction
        val label = String.format(Locale.GERMANY, "%.3f", price)
        val laidOut = measurer.measure(label, TextStyle(fontSize = 10.sp, color = TankRadarTextDim))
        drawText(laidOut, topLeft = Offset(0f, y - laidOut.size.height / 2f))
    }
}

private fun DrawScope.drawSeries(points: List<Offset>, color: Color, dashed: Boolean) {
    if (points.size < 2) return
    val path = Path().apply {
        moveTo(points.first().x, points.first().y)
        points.drop(1).forEach { lineTo(it.x, it.y) }
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(
            width = 2.5.dp.toPx(),
            pathEffect = if (dashed) {
                androidx.compose.ui.graphics.PathEffect.dashPathEffect(
                    floatArrayOf(10f, 8f)
                )
            } else {
                null
            },
        ),
    )
}

/** Tiny 24h trend line shown on a station card. */
@Composable
fun Sparkline(prices: List<Double>, modifier: Modifier = Modifier) {
    if (prices.size < 2) {
        Box(modifier)
        return
    }
    val color = when {
        prices.last() < prices.first() -> TankRadarSuccess
        prices.last() > prices.first() -> TankRadarDanger
        else -> TankRadarTextDim
    }
    val low = prices.min()
    val high = prices.max()
    val span = (high - low).takeIf { it > 0.0001 } ?: 1.0

    Canvas(modifier.fillMaxSize()) {
        val stepX = size.width / (prices.size - 1).toFloat()
        val points = prices.mapIndexed { index, price ->
            Offset(stepX * index, size.height - (size.height * ((price - low) / span)).toFloat())
        }
        drawSeries(points, color, dashed = false)
    }
}
