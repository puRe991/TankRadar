package de.tankradar.tagebuch.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.tankradar.tagebuch.data.FuelType
import de.tankradar.tagebuch.data.VehicleSettings
import java.time.LocalDate

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: VehicleSettings,
    entryCount: Int,
    onSave: (VehicleSettings) -> Unit,
    exportCsv: () -> String,
    onImport: (String) -> Unit,
    onMessage: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var name by remember(settings) { mutableStateOf(settings.name) }
    var capacity by remember(settings) { mutableStateOf(settings.tankCapacity.forInput(1)) }
    var fuel by remember(settings) { mutableStateOf(settings.defaultFuel) }
    val cap = capacity.parseDecimal()
    val capValid = cap != null && cap in 5.0..200.0

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)!!.use { it.write(exportCsv().toByteArray()) }
        }.onSuccess { onMessage("Sicherung gespeichert") }
            .onFailure { onMessage("Export fehlgeschlagen") }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
        }.onSuccess(onImport).onFailure { onMessage("Datei konnte nicht gelesen werden") }
    }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("Fahrzeug") {
            OutlinedTextField(
                value = name, onValueChange = { name = it.take(40) },
                label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = capacity,
                onValueChange = { v -> capacity = v.filter { it.isDigit() || it == ',' || it == '.' }.take(6) },
                label = { Text("Tankgröße") },
                suffix = { Text("Liter") },
                isError = !capValid,
                supportingText = { Text("Steht im Fahrzeugschein oder Handbuch – wichtig für Reichweite und Tankdauer.") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text("Standard-Kraftstoff", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FuelType.entries.forEach { f ->
                    FilterChip(selected = fuel == f, onClick = { fuel = f }, label = { Text(f.label) })
                }
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { onSave(VehicleSettings(name.trim().ifEmpty { "Mein Auto" }, cap!!, fuel)) },
                enabled = capValid,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Speichern") }
        }

        SectionCard("Datensicherung") {
            Text(
                "Deine Daten liegen nur auf diesem Handy. Exportiere sie ab und zu als CSV-Datei " +
                    "(lässt sich auch in Excel öffnen) – z. B. vor einem Handywechsel.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { exportLauncher.launch("tank-tagebuch-${LocalDate.now()}.csv") },
                    enabled = entryCount > 0,
                    modifier = Modifier.weight(1f),
                ) { Text("Exportieren") }
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("text/*", "application/octet-stream")) },
                    modifier = Modifier.weight(1f),
                ) { Text("Importieren") }
            }
        }

        SectionCard("So rechnet die App") {
            listOf(
                "Kosten & Liter pro Monat: aus den Abständen zwischen deinen Tankvorgängen hochgerechnet. " +
                    "Jüngere Einträge zählen stärker (Halbwertszeit ca. 4 Monate), so passt sich die Prognose an.",
                "Verbrauch: Volltank-Methode – alles, was zwischen zwei Volltankungen nachgetankt wurde, " +
                    "geteilt durch die gefahrenen km. Teilbetankungen werden korrekt mitgezählt.",
                "Tankdauer: Tankgröße geteilt durch deinen durchschnittlichen Tagesverbrauch.",
                "Reichweite: Tankgröße geteilt durch deinen aktuellen Verbrauch.",
                "Offensichtliche Tippfehler (z. B. 80 L/100 km) werden beim Verbrauch ignoriert.",
            ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 3.dp)) }
        }
        Spacer(Modifier.height(24.dp))
    }
}
