package de.tankradar.tagebuch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.tankradar.tagebuch.data.VehicleSettings
import de.tankradar.tagebuch.logic.Analysis
import de.tankradar.tagebuch.logic.Confidence
import de.tankradar.tagebuch.logic.DrivingTrend
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
fun OverviewScreen(a: Analysis, settings: VehicleSettings, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Header(a, settings) }

        if (a.entryCount == 0) {
            item { EmptyState() }
            return@LazyColumn
        }

        item { MonthlyCard(a) }
        item { TankCard(a, settings) }
        item { ConsumptionCard(a) }
        insights(a).takeIf { it.isNotEmpty() }?.let { list ->
            item {
                SectionCard("Erkenntnisse") {
                    list.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 3.dp)) }
                }
            }
        }
        if (a.months.isNotEmpty()) item { MonthChartCard(a) }
        if (a.consumptionSegments.size >= 2) item { ConsumptionChartCard(a) }
        item { PriceCard(a) }
        item { TotalsCard(a) }
    }
}

@Composable
private fun Header(a: Analysis, settings: VehicleSettings) {
    Column {
        Text(settings.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "${a.entryCount} Tankvorgänge · ${settings.tankCapacity.num(0)} L Tank",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        val progress = (Confidence.entries.indexOf(a.confidence).toFloat() / (Confidence.entries.size - 1))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Prognose: ${a.confidence.label}", style = MaterialTheme.typography.labelLarge)
            a.entriesToNextLevel?.let {
                Text(
                    if (it == 1) "noch 1 Eintrag bis besser" else "noch $it Einträge bis besser",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun EmptyState() {
    SectionCard("Los geht's") {
        Text(
            "Trag nach jedem Tanken ein, wie viele Liter du für welchen Preis getankt hast. " +
                "Ab dem zweiten Eintrag rechnet die App aus, was dich das Auto im Monat kostet " +
                "und wie lange eine Tankfüllung hält.\n\n" +
                "Tipp: Wenn du zusätzlich den Kilometerstand einträgst und immer vollmachst, " +
                "bekommst du auch deinen echten Verbrauch (L/100 km) und eine Reichweiten-Prognose.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun MonthlyCard(a: Analysis) {
    SectionCard("Pro Monat", container = MaterialTheme.colorScheme.primaryContainer) {
        if (a.costPerMonth == null) {
            Text("Ab dem zweiten Tankvorgang (an einem anderen Tag) siehst du hier deine monatlichen Kosten.")
            return@SectionCard
        }
        Row(Modifier.fillMaxWidth()) {
            BigStat(
                "Kosten",
                a.costPerMonth.euro(),
                a.costPerMonthAtCurrentPrice?.let { "zum letzten Preis: ${it.euro()}" },
                Modifier.weight(1f),
            )
            BigStat(
                "Getankt",
                "${a.litersPerMonth!!.num(0)} L",
                a.kmPerMonth?.let { "≈ ${it.num(0)} km gefahren" },
                Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Hochgerechnet aus ${a.trackedDays} Tagen, neuere Einträge zählen stärker. " +
                "Aufs Jahr: ca. ${(a.costPerMonth * 12).euro()}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun TankCard(a: Analysis, settings: VehicleSettings) {
    SectionCard("Wie lange hält der Tank?", container = MaterialTheme.colorScheme.secondaryContainer) {
        if (a.daysPerFullTank == null && a.rangePerFullTank == null) {
            Text("Braucht mindestens zwei Tankvorgänge an verschiedenen Tagen.")
            return@SectionCard
        }
        Row(Modifier.fillMaxWidth()) {
            a.daysPerFullTank?.let {
                BigStat("Volle Füllung reicht", "≈ ${it.roundToInt()} Tage", "${settings.tankCapacity.num(0)} L Tank", Modifier.weight(1f))
            }
            a.rangePerFullTank?.let {
                BigStat("Reichweite", "≈ ${it.roundToInt()} km", "pro Tankfüllung", Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(8.dp))
        a.avgDaysBetweenRefuels?.let { StatRow("Du tankst im Schnitt alle", "${it.num(1)} Tage") }
        a.nextRefuelDate?.let { date ->
            val days = ChronoUnit.DAYS.between(LocalDate.now(), date)
            val text = when {
                days <= 0L -> "heute fällig"
                days == 1L -> "morgen"
                else -> "in $days Tagen"
            }
            StatRow("Nächstes Tanken voraussichtlich", text, date.deShort())
        }
    }
}

@Composable
private fun ConsumptionCard(a: Analysis) {
    SectionCard("Verbrauch & Fahrverhalten") {
        if (a.avgConsumption == null) {
            Text(
                "Für den Verbrauch trag beim Tanken den Kilometerstand (oder die gefahrenen km) ein " +
                    "und mach den Tank voll. Ab zwei Volltankungen mit km-Angabe geht's los.",
                style = MaterialTheme.typography.bodyMedium,
            )
            a.kmPerMonth?.let { StatRow("Gefahren pro Monat", "≈ ${it.num(0)} km") }
            return@SectionCard
        }
        Row(Modifier.fillMaxWidth()) {
            BigStat("Ø Verbrauch", "${a.avgConsumption.num(1)} L", "pro 100 km", Modifier.weight(1f))
            a.recentConsumption?.takeIf { a.consumptionSegments.size >= 2 }?.let {
                BigStat("Zuletzt", "${it.num(1)} L", "letzte ${minOf(3, a.consumptionSegments.size)} Füllungen", Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(8.dp))
        a.drivingTrend?.let { trend ->
            val pct = abs(a.trendPercent ?: 0.0).num(0)
            val text = when (trend) {
                DrivingTrend.THRIFTIER -> "Du fährst zurzeit sparsamer als sonst (−$pct %). Weiter so!"
                DrivingTrend.HIGHER -> "Dein Verbrauch ist zuletzt gestiegen (+$pct %). Kurzstrecke, Winter, schneller gefahren?"
                DrivingTrend.NORMAL -> "Dein Verbrauch ist stabil – du fährst wie gewohnt."
            }
            Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
        }
        a.costPer100Km?.let { StatRow("Spritkosten pro 100 km", it.euro(), "pro km: ${(it / 100).euro()}") }
        a.kmPerMonth?.let { StatRow("Gefahren pro Monat", "≈ ${it.num(0)} km") }
    }
}

@Composable
private fun MonthChartCard(a: Analysis) {
    // Lückenlose Monatsreihe der letzten bis zu 12 Monate.
    val last = a.months.last().month
    val first = maxOf(a.months.first().month, last.minusMonths(11))
    val byMonth = a.months.associateBy { it.month }
    val months = generateSequence(first) { it.plusMonths(1) }.takeWhile { !it.isAfter(last) }.toList()
    SectionCard("Ausgaben pro Monat") {
        BarChart(
            labels = months.map { it.deShort() },
            values = months.map { byMonth[it]?.cost ?: 0.0 },
            valueLabel = { "${it.roundToInt()} €" },
        )
        Spacer(Modifier.height(8.dp))
        months.takeLast(4).reversed().forEach { m ->
            val s = byMonth[m]
            StatRow(
                m.deLong() + if (m == YearMonth.now()) " (laufend)" else "",
                (s?.cost ?: 0.0).euro(),
                s?.let { "${it.liters.num(1)} L" + (it.km?.let { km -> " · ${km.num(0)} km" } ?: "") },
            )
        }
    }
}

@Composable
private fun ConsumptionChartCard(a: Analysis) {
    val segs = a.consumptionSegments.takeLast(15)
    SectionCard("Verbrauchsverlauf (L/100 km)") {
        LineChart(
            labels = segs.map { "${it.endDate.dayOfMonth}.${it.endDate.monthValue}." },
            values = segs.map { it.per100km },
            average = a.avgConsumption,
            valueLabel = { it.num(1) },
        )
        Text(
            "Gestrichelt: dein Durchschnitt",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PriceCard(a: Analysis) {
    SectionCard("Spritpreise") {
        a.avgPricePerLiter?.let { StatRow("Ø bezahlter Preis", it.pricePerLiter()) }
        a.lastPrice?.let { StatRow("Zuletzt", it.pricePerLiter()) }
        if (a.entryCount >= 2) {
            a.minPrice?.let { StatRow("Am günstigsten", it.pricePerLiter()) }
            a.maxPrice?.let { StatRow("Am teuersten", it.pricePerLiter()) }
        }
    }
}

@Composable
private fun TotalsCard(a: Analysis) {
    SectionCard("Insgesamt") {
        StatRow("Getankt", "${a.totalLiters.num(1)} L")
        StatRow("Ausgegeben", a.totalCost.euro())
        a.totalKm?.let { StatRow("Erfasste Strecke", "${it.num(0)} km") }
        if (a.trackedDays > 0) StatRow("Zeitraum", "${a.trackedDays} Tage")
        HorizontalDivider(Modifier.padding(vertical = 6.dp))
        Text(
            "Je mehr du einträgst, desto genauer werden die Prognosen. Ältere Einträge verlieren " +
                "nach und nach an Gewicht, damit sich die App an dein aktuelles Fahrverhalten anpasst.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Kurze, verständliche Hinweise aus den Daten. */
private fun insights(a: Analysis): List<String> = buildList {
    val avg = a.avgPricePerLiter
    val last = a.lastPrice
    if (a.entryCount >= 3 && avg != null && last != null) {
        val diffCent = (last - avg) * 100
        when {
            diffCent >= 3 -> add("Dein letzter Literpreis lag ${diffCent.num(0)} Cent über deinem Schnitt – abends tanken ist oft günstiger.")
            diffCent <= -3 -> add("Gut getankt: Dein letzter Literpreis lag ${(-diffCent).num(0)} Cent unter deinem Schnitt.")
        }
    }
    val min = a.minPrice
    val max = a.maxPrice
    if (a.entryCount >= 4 && min != null && max != null && a.litersPerMonth != null && max - min >= 0.05) {
        val saving = (avg!! - min) * a.litersPerMonth
        if (saving >= 2) add("Würdest du immer zum günstigsten Preis tanken, den du schon bezahlt hast, sparst du ca. ${saving.euro()} im Monat.")
    }
    if (a.costPerMonth != null && a.costPerMonthAtCurrentPrice != null) {
        val d = a.costPerMonthAtCurrentPrice - a.costPerMonth
        if (abs(d) >= 5) {
            add(
                if (d > 0) "Bleibt der Preis so wie zuletzt, wird es ca. ${d.euro()} pro Monat teurer als bisher."
                else "Bleibt der Preis so wie zuletzt, sparst du ca. ${(-d).euro()} pro Monat gegenüber bisher."
            )
        }
    }
    if (a.entryCount >= 3 && a.totalKm == null) {
        add("Trag auch die Kilometer ein, dann siehst du deinen Verbrauch und die Reichweite.")
    }
}
