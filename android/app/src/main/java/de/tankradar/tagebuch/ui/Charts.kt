package de.tankradar.tagebuch.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Einfaches Balkendiagramm mit Beschriftung unter und Wert über jedem Balken. */
@Composable
fun BarChart(labels: List<String>, values: List<Double>, valueLabel: (Double) -> String, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val barColor = MaterialTheme.colorScheme.primary
    val lastColor = MaterialTheme.colorScheme.secondary
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val small = TextStyle(fontSize = 10.sp, color = textColor)

    Canvas(modifier.fillMaxWidth().height(180.dp)) {
        if (values.isEmpty()) return@Canvas
        val max = values.max().coerceAtLeast(0.01)
        val labelSpace = 18.dp.toPx()
        val valueSpace = 16.dp.toPx()
        val chartH = size.height - labelSpace - valueSpace
        val slot = size.width / values.size
        val barW = minOf(slot * 0.6f, 36.dp.toPx())
        values.forEachIndexed { i, v ->
            val h = (v / max * chartH).toFloat()
            val x = i * slot + (slot - barW) / 2
            val top = valueSpace + chartH - h
            drawRoundRect(
                color = if (i == values.lastIndex) lastColor else barColor,
                topLeft = Offset(x, top),
                size = Size(barW, h.coerceAtLeast(1f)),
                cornerRadius = CornerRadius(4.dp.toPx()),
            )
            val label = measurer.measure(labels[i], small)
            drawText(label, topLeft = Offset(i * slot + (slot - label.size.width) / 2, size.height - label.size.height))
            if (values.size <= 8 || i == values.lastIndex || v == values.max()) {
                val vl = measurer.measure(valueLabel(v), small)
                drawText(vl, topLeft = Offset(i * slot + (slot - vl.size.width) / 2, (top - vl.size.height - 2f).coerceAtLeast(0f)))
            }
        }
    }
}

/** Liniendiagramm mit gestrichelter Durchschnittslinie. */
@Composable
fun LineChart(labels: List<String>, values: List<Double>, average: Double?, valueLabel: (Double) -> String, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val lineColor = MaterialTheme.colorScheme.primary
    val avgColor = MaterialTheme.colorScheme.secondary
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val small = TextStyle(fontSize = 10.sp, color = textColor)

    Canvas(modifier.fillMaxWidth().height(170.dp)) {
        if (values.size < 2) return@Canvas
        val minV = (values.min()).let { if (average != null) minOf(it, average) else it }
        val maxV = (values.max()).let { if (average != null) maxOf(it, average) else it }
        val range = (maxV - minV).coerceAtLeast(0.5)
        val lo = minV - range * 0.15
        val hi = maxV + range * 0.15
        val labelSpace = 18.dp.toPx()
        val left = 36.dp.toPx()
        val chartH = size.height - labelSpace
        val chartW = size.width - left
        fun y(v: Double) = ((hi - v) / (hi - lo) * chartH).toFloat()
        fun x(i: Int) = left + chartW * i / (values.size - 1)

        // Achsenbeschriftung oben/unten
        drawText(measurer.measure(valueLabel(hi), small), topLeft = Offset(0f, 0f))
        drawText(measurer.measure(valueLabel(lo), small), topLeft = Offset(0f, chartH - 14.dp.toPx()))

        if (average != null) {
            drawLine(
                avgColor, Offset(left, y(average)), Offset(size.width, y(average)),
                strokeWidth = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)),
            )
        }
        val path = Path()
        values.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
        drawPath(path, lineColor, style = Stroke(width = 2.5.dp.toPx()))
        values.forEachIndexed { i, v -> drawCircle(lineColor, 3.5.dp.toPx(), Offset(x(i), y(v))) }

        // Nur einige Datumslabels zeigen, damit nichts überlappt.
        val step = ((values.size + 5) / 6).coerceAtLeast(1)
        labels.forEachIndexed { i, l ->
            if (i % step == 0 || i == labels.lastIndex) {
                val m = measurer.measure(l, small)
                val lx = (x(i) - m.size.width / 2).coerceIn(left, size.width - m.size.width)
                drawText(m, topLeft = Offset(lx, size.height - m.size.height))
            }
        }
    }
}
