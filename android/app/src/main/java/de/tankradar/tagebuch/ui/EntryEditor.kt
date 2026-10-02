package de.tankradar.tagebuch.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import de.tankradar.tagebuch.data.FuelType
import de.tankradar.tagebuch.data.RefuelEntry
import de.tankradar.tagebuch.data.VehicleSettings
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.abs

private enum class KmMode(val label: String) { ODOMETER("Kilometerstand"), TRIP("Gefahrene km") }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EntryEditor(
    existing: RefuelEntry?,
    previous: RefuelEntry?,
    settings: VehicleSettings,
    newId: () -> Long,
    onSave: (RefuelEntry) -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var date by remember { mutableStateOf(existing?.date ?: LocalDate.now()) }
    var fuel by remember { mutableStateOf(existing?.fuelType ?: previous?.fuelType ?: settings.defaultFuel) }
    var liters by remember { mutableStateOf(existing?.liters?.forInput(2) ?: "") }
    var price by remember { mutableStateOf(existing?.pricePerLiter?.forInput(3) ?: "") }
    var total by remember { mutableStateOf(existing?.totalCost?.forInput(2) ?: "") }
    var fullTank by remember { mutableStateOf(existing?.fullTank ?: true) }
    var kmMode by remember {
        mutableStateOf(
            when {
                existing?.tripKm != null -> KmMode.TRIP
                existing?.odometer != null -> KmMode.ODOMETER
                previous?.tripKm != null && previous.odometer == null -> KmMode.TRIP
                else -> KmMode.ODOMETER
            }
        )
    }
    var odometer by remember { mutableStateOf(existing?.odometer?.forInput(0) ?: "") }
    var trip by remember { mutableStateOf(existing?.tripKm?.forInput(1) ?: "") }
    var station by remember { mutableStateOf(existing?.station ?: previous?.station ?: "") }
    var note by remember { mutableStateOf(existing?.note ?: "") }
    var showDate by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var triedSave by remember { mutableStateOf(false) }

    // Welche zwei Felder der Nutzer zuletzt angefasst hat bestimmt, welches dritte berechnet wird.
    var lastEdited by remember { mutableStateOf(listOf("liters", "price")) }
    fun touched(field: String) {
        lastEdited = (listOf(field) + lastEdited.filter { it != field }).take(2)
        val l = liters.parseDecimal()
        val p = price.parseDecimal()
        val t = total.parseDecimal()
        when {
            "total" !in lastEdited && l != null && p != null -> total = (l * p).forInput(2)
            "price" !in lastEdited && l != null && t != null && l > 0 -> price = (t / l).forInput(3)
            "liters" !in lastEdited && p != null && t != null && p > 0 -> liters = (t / p).forInput(2)
        }
    }

    val l = liters.parseDecimal()
    val p = price.parseDecimal()
    val t = total.parseDecimal()
    val odo = odometer.parseDecimal()
    val tripKm = trip.parseDecimal()
    val prevOdo = previous?.odometer
    val odoError = kmMode == KmMode.ODOMETER && odo != null && prevOdo != null && odo <= prevOdo
    val capacityWarning = l != null && l > settings.tankCapacity * 1.05
    val mismatch = l != null && p != null && t != null && abs(l * p - t) > 0.05 + t * 0.01
    val valid = l != null && l > 0 && p != null && p > 0 && t != null && t > 0 && !odoError

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (existing == null) "Tanken eintragen" else "Eintrag bearbeiten") },
                    navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Schließen") } },
                    actions = {
                        if (onDelete != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Löschen") }
                    },
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = date.de(),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Datum") },
                    trailingIcon = { IconButton(onClick = { showDate = true }) { Icon(Icons.Filled.DateRange, "Datum wählen") } },
                    modifier = Modifier.fillMaxWidth(),
                )

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    FuelType.entries.forEach { f ->
                        FilterChip(selected = fuel == f, onClick = { fuel = f }, label = { Text(f.label) })
                    }
                }

                NumberField("Getankte Liter", liters, "L", triedSave && (l == null || l <= 0)) { liters = it; touched("liters") }
                if (capacityWarning) Hint("Mehr als dein Tank fasst (${settings.tankCapacity.num(0)} L) – Tankgröße unter „Fahrzeug“ prüfen.")
                NumberField("Preis pro Liter", price, "€/L", triedSave && (p == null || p <= 0)) { price = it; touched("price") }
                NumberField("Gesamtbetrag", total, "€", triedSave && (t == null || t <= 0)) { total = it; touched("total") }
                if (mismatch) Hint("Liter × Preis ergibt ${(l!! * p!!).euro()} – stimmt der Betrag?")

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Vollgetankt", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Wichtig für die Verbrauchsberechnung",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = fullTank, onCheckedChange = { fullTank = it })
                }

                Text("Kilometer (optional)", style = MaterialTheme.typography.titleSmall)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    KmMode.entries.forEachIndexed { i, m ->
                        SegmentedButton(
                            selected = kmMode == m,
                            onClick = { kmMode = m },
                            shape = SegmentedButtonDefaults.itemShape(i, KmMode.entries.size),
                        ) { Text(m.label) }
                    }
                }
                when (kmMode) {
                    KmMode.ODOMETER -> {
                        NumberField("Kilometerstand", odometer, "km", odoError, integer = true) { odometer = it }
                        when {
                            odoError -> Hint("Muss größer sein als beim letzten Tanken (${prevOdo!!.num(0)} km).")
                            odo != null && prevOdo != null -> Hint("Seit dem letzten Tanken: ${(odo - prevOdo).num(0)} km")
                            prevOdo != null -> Hint("Letzter Stand: ${prevOdo.num(0)} km")
                        }
                    }
                    KmMode.TRIP -> {
                        NumberField("Gefahren seit letztem Tanken", trip, "km", false) { trip = it }
                        Hint("Z. B. vom Tageskilometerzähler – danach am besten zurücksetzen.")
                    }
                }

                OutlinedTextField(
                    value = station, onValueChange = { station = it.take(60) },
                    label = { Text("Tankstelle (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note, onValueChange = { note = it.take(200) },
                    label = { Text("Notiz (optional)") }, modifier = Modifier.fillMaxWidth(),
                )

                Button(
                    onClick = {
                        triedSave = true
                        if (!valid) return@Button
                        onSave(
                            RefuelEntry(
                                id = existing?.id ?: newId(),
                                date = date,
                                liters = l!!,
                                pricePerLiter = p!!,
                                totalCost = t!!,
                                fuelType = fuel,
                                fullTank = fullTank,
                                odometer = if (kmMode == KmMode.ODOMETER) odo?.takeIf { it > 0 } else null,
                                tripKm = if (kmMode == KmMode.TRIP) tripKm?.takeIf { it > 0 } else null,
                                station = station.trim(),
                                note = note.trim(),
                            )
                        )
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                ) { Text("Speichern") }
                if (triedSave && !valid) Hint("Bitte Liter, Preis und Betrag ausfüllen.")
            }
        }
    }

    if (showDate) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),
        )
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    showDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Abbrechen") } },
        ) { DatePicker(state = state) }
    }

    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Eintrag löschen?") },
            text = { Text("Der Tankvorgang vom ${date.de()} wird entfernt.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("Löschen") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    suffix: String,
    isError: Boolean,
    integer: Boolean = false,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { input ->
            val filtered = input.filter { it.isDigit() || (!integer && (it == ',' || it == '.')) }
            onChange(filtered.take(10))
        },
        label = { Text(label) },
        suffix = { Text(suffix) },
        isError = isError,
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (integer) KeyboardType.Number else KeyboardType.Decimal,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
