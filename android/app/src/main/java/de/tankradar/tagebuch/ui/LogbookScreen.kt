package de.tankradar.tagebuch.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.tankradar.tagebuch.data.RefuelEntry
import de.tankradar.tagebuch.logic.Analysis
import de.tankradar.tagebuch.logic.Analyzer
import java.time.YearMonth

@Composable
fun LogbookScreen(
    entries: List<RefuelEntry>,
    analysis: Analysis,
    onEdit: (RefuelEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) {
        Column(modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
            Text("Noch keine Einträge", style = MaterialTheme.typography.titleLarge)
            Text("Tippe auf „Tanken eintragen“, um deinen ersten Tankvorgang zu erfassen.")
        }
        return
    }
    val distances = Analyzer.distancesSincePrevious(entries)
    val consumptionByDate = analysis.consumptionSegments.associateBy { it.endDate }
    val rows = entries.indices.reversed().toList()
    val byMonth = rows.groupBy { YearMonth.from(entries[it].date) }
    val monthSums = analysis.months.associateBy { it.month }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        byMonth.forEach { (month, idx) ->
            item(key = "m$month") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(month.deLong(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    monthSums[month]?.let {
                        Text(
                            "${it.liters.num(1)} L · ${it.cost.euro()}",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            items(idx, key = { entries[it].id }) { i ->
                val e = entries[i]
                val seg = if (e.fullTank) consumptionByDate[e.date] else null
                EntryCard(e, distances[i], seg?.per100km, onClick = { onEdit(e) })
            }
        }
    }
}

@Composable
private fun EntryCard(e: RefuelEntry, km: Double?, per100: Double?, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(e.date.deShort(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        buildString {
                            append(e.fuelType.label)
                            if (!e.fullTank) append(" · teilgetankt")
                            if (e.station.isNotBlank()) append(" · ${e.station}")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(e.totalCost.euro(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("${e.liters.num(2)} L × ${e.pricePerLiter.pricePerLiter()}", style = MaterialTheme.typography.bodyMedium)
                val kmText = listOfNotNull(
                    km?.let { "+${it.num(0)} km" },
                    per100?.let { "${it.num(1)} L/100" },
                ).joinToString(" · ")
                if (kmText.isNotEmpty()) {
                    Text(kmText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            e.odometer?.let {
                Text("Kilometerstand ${it.num(0)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (e.note.isNotBlank()) {
                Text(e.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
