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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.tankradar.app.data.SettingsRepository
import de.tankradar.app.data.TankRadarSettings
import de.tankradar.app.data.remote.FuelType
import de.tankradar.app.ui.theme.TankRadarTextDim
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = SettingsRepository(app)

    val settings: StateFlow<TankRadarSettings> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TankRadarSettings())

    fun setPostCode(value: String) = viewModelScope.launch { repository.setPostCode(value) }
    fun setRadius(value: Int) = viewModelScope.launch { repository.setRadiusKm(value) }
    fun setFuelType(value: FuelType) = viewModelScope.launch { repository.setFuelType(value) }
    fun setAutoUpdate(value: Boolean) = viewModelScope.launch { repository.setAutoUpdate(value) }
    fun setInterval(value: Int) = viewModelScope.launch { repository.setUpdateInterval(value) }
    fun setRetention(value: Int) = viewModelScope.launch { repository.setHistoryRetentionDays(value) }
}

@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    viewModel: SettingsViewModel = viewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Einstellungen",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        SettingsCard("Suchgebiet") {
            var postCode by remember(settings.postCode) { mutableStateOf(settings.postCode) }
            OutlinedTextField(
                value = postCode,
                onValueChange = { input ->
                    // German post codes are exactly five digits; filtering here
                    // stops an unusable query from ever reaching the endpoint.
                    postCode = input.filter { it.isDigit() }.take(5)
                    if (postCode.length == 5) viewModel.setPostCode(postCode)
                },
                label = { Text("Postleitzahl") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Text("Umkreis: ${settings.radiusKm} km", fontSize = 13.sp)
            Slider(
                value = settings.radiusKm.toFloat(),
                onValueChange = { viewModel.setRadius(it.toInt()) },
                valueRange = 1f..25f,
                steps = 23,
            )
        }

        SettingsCard("Bevorzugte Kraftstoffart") {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FuelType.entries.forEach { type ->
                    FilterChip(
                        selected = settings.fuelType == type,
                        onClick = { viewModel.setFuelType(type) },
                        label = { Text(type.label, fontSize = 11.sp) },
                    )
                }
            }
        }

        SettingsCard("Automatische Aktualisierung") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Im Hintergrund aktualisieren", fontSize = 14.sp)
                    Text(
                        "Sammelt laufend Preise — das ist die Grundlage für Prognose und Prüffälle.",
                        color = TankRadarTextDim,
                        fontSize = 12.sp,
                    )
                }
                Switch(
                    checked = settings.autoUpdate,
                    onCheckedChange = viewModel::setAutoUpdate,
                )
            }
            if (settings.autoUpdate) {
                Spacer(Modifier.height(12.dp))
                Text("Alle ${settings.updateIntervalMinutes} Minuten", fontSize = 13.sp)
                Slider(
                    value = settings.updateIntervalMinutes.toFloat(),
                    onValueChange = { viewModel.setInterval(it.toInt()) },
                    valueRange = TankRadarSettings.MIN_INTERVAL_MINUTES.toFloat()..180f,
                    steps = 10,
                )
                Text(
                    "Android führt Hintergrundaufgaben frühestens alle " +
                        "${TankRadarSettings.MIN_INTERVAL_MINUTES} Minuten aus und verschiebt sie, " +
                        "um Akku zu sparen. Der Wert ist deshalb ein Ziel, keine Garantie.",
                    color = TankRadarTextDim,
                    fontSize = 11.sp,
                )
            }
        }

        SettingsCard("Datenhaltung") {
            Text("Verlauf ${settings.historyRetentionDays} Tage aufbewahren", fontSize = 13.sp)
            Slider(
                value = settings.historyRetentionDays.toFloat(),
                onValueChange = { viewModel.setRetention(it.toInt()) },
                valueRange = 30f..365f,
                steps = 10,
            )
            Text(
                "Ältere Preise werden bei der nächsten Aktualisierung gelöscht. " +
                    "Die Prüffall-Auswertung betrachtet ohnehin nur 30 Tage.",
                color = TankRadarTextDim,
                fontSize = 11.sp,
            )
        }

        SettingsCard("Über TankRadar") {
            Text(
                "Alle Daten liegen ausschließlich auf diesem Gerät. TankRadar fragt die " +
                    "Preise direkt beim ADAC ab — es gibt keinen TankRadar-Server und keine " +
                    "Übertragung an Dritte.",
                color = TankRadarTextDim,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Eine Preisänderung nach 12:00 Uhr ist nicht automatisch rechtswidrig. " +
                    "Die Prüffall-Ansicht sammelt ausschließlich Nachweisdaten.",
                color = TankRadarTextDim,
                fontSize = 12.sp,
            )
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}
