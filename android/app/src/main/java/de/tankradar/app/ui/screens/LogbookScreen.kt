package de.tankradar.app.ui.screens

import android.app.Application
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.tankradar.app.data.PriceRepository
import de.tankradar.app.data.db.RefuelLogEntity
import de.tankradar.app.data.remote.FuelType
import de.tankradar.app.ui.Format
import de.tankradar.app.ui.theme.TankRadarTextDim
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.YearMonth

data class LogbookUiState(
    val entries: List<RefuelLogEntity> = emptyList(),
    val monthSpend: Double = 0.0,
    val monthLiters: Double = 0.0,
    val averagePrice: Double = 0.0,
)

class LogbookViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = PriceRepository.create(app)

    val uiState: StateFlow<LogbookUiState> = repository.observeRefuelLogs()
        .map { entries ->
            val thisMonth = YearMonth.now()
            val current = entries.filter {
                YearMonth.from(Format.toLocalDateTime(it.timestamp)) == thisMonth
            }
            val liters = current.sumOf { it.liters }
            LogbookUiState(
                entries = entries,
                monthSpend = current.sumOf { it.totalCost },
                monthLiters = liters,
                // Litre-weighted, not the mean of the per-litre prices: a 5 L and a
                // 50 L fill-up must not count equally.
                averagePrice = if (liters > 0) current.sumOf { it.totalCost } / liters else 0.0,
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LogbookUiState())

    fun add(entry: RefuelLogEntity) = viewModelScope.launch { repository.addRefuelEntry(entry) }

    fun delete(entryId: Long) = viewModelScope.launch { repository.deleteRefuelEntry(entryId) }
}

@Composable
fun LogbookScreen(
    contentPadding: PaddingValues,
    viewModel: LogbookViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showDialog by remember { mutableStateOf(false) }

    if (showDialog) {
        AddRefuelDialog(
            onDismiss = { showDialog = false },
            onSave = {
                viewModel.add(it)
                showDialog = false
            },
        )
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    "Tank-Tagebuch",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text("Diesen Monat", color = TankRadarTextDim, fontSize = 13.sp)
            }
        }

        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                KpiCard("Ausgaben", Format.euro(state.monthSpend), Modifier.weight(1f))
                KpiCard("Menge", Format.liters(state.monthLiters), Modifier.weight(1f))
                KpiCard("Ø Preis", Format.priceWithUnit(state.averagePrice), Modifier.weight(1f))
            }
        }

        item {
            Row(Modifier.padding(horizontal = 16.dp)) {
                Button(onClick = { showDialog = true }, Modifier.fillMaxWidth()) {
                    Text("Tankvorgang erfassen")
                }
            }
        }

        if (state.entries.isEmpty()) {
            item {
                EmptyState(
                    title = "Noch keine Einträge",
                    body = "Erfasse deine Tankvorgänge, um Ausgaben und Durchschnittspreise " +
                        "im Blick zu behalten.",
                )
            }
        }

        items(state.entries, key = { it.id }) { entry ->
            LogEntryCard(entry, onDelete = { viewModel.delete(entry.id) })
        }

        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun KpiCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(value, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(label, color = TankRadarTextDim, fontSize = 11.sp)
        }
    }
}

@Composable
private fun LogEntryCard(entry: RefuelLogEntity, onDelete: () -> Unit) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(Modifier.padding(14.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    entry.stationNameFallback ?: "Tankvorgang",
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "${Format.dateTime(Format.toLocalDateTime(entry.timestamp))} · " +
                        FuelType.labelFor(entry.fuelType),
                    color = TankRadarTextDim,
                    fontSize = 12.sp,
                )
                Text(
                    "${Format.liters(entry.liters)} · ${Format.priceWithUnit(entry.pricePerLiter)}",
                    color = TankRadarTextDim,
                    fontSize = 12.sp,
                )
                entry.notes?.takeIf { it.isNotBlank() }?.let {
                    Text(it, color = TankRadarTextDim, fontSize = 12.sp)
                }
            }
            Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                Text(Format.euro(entry.totalCost), fontWeight = FontWeight.Bold)
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "Eintrag löschen", tint = TankRadarTextDim)
                }
            }
        }
    }
}

@Composable
private fun AddRefuelDialog(
    onDismiss: () -> Unit,
    onSave: (RefuelLogEntity) -> Unit,
) {
    var stationName by remember { mutableStateOf("") }
    var fuelType by remember { mutableStateOf(FuelType.DEFAULT) }
    var liters by remember { mutableStateOf("") }
    var pricePerLiter by remember { mutableStateOf("") }
    var odometer by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }

    // German keyboards produce a comma; accepting only a dot would make the
    // dialog impossible to fill in on most devices.
    fun parse(value: String): Double? = value.replace(',', '.').toDoubleOrNull()

    val litersValue = parse(liters)
    val priceValue = parse(pricePerLiter)
    val canSave = litersValue != null && litersValue > 0 && priceValue != null && priceValue > 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Neuer Tankvorgang") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = stationName,
                    onValueChange = { stationName = it },
                    label = { Text("Tankstelle (optional)") },
                    singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FuelType.entries.forEach { type ->
                        FilterChip(
                            selected = fuelType == type,
                            onClick = { fuelType = type },
                            label = { Text(type.label, fontSize = 11.sp) },
                        )
                    }
                }
                OutlinedTextField(
                    value = liters,
                    onValueChange = { liters = it },
                    label = { Text("Menge (Liter)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = pricePerLiter,
                    onValueChange = { pricePerLiter = it },
                    label = { Text("Preis (€/L)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = odometer,
                    onValueChange = { odometer = it },
                    label = { Text("Kilometerstand (optional)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notiz (optional)") },
                    singleLine = true,
                )
                if (canSave) {
                    Text(
                        "Gesamt: ${Format.euro(litersValue!! * priceValue!!)}",
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
                    onSave(
                        RefuelLogEntity(
                            stationNameFallback = stationName.trim().ifBlank { null },
                            timestamp = System.currentTimeMillis(),
                            fuelType = fuelType.key,
                            liters = litersValue!!,
                            pricePerLiter = priceValue!!,
                            totalCost = litersValue * priceValue,
                            odometer = odometer.trim().toIntOrNull(),
                            notes = notes.trim().ifBlank { null },
                        )
                    )
                },
            ) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
