package de.tankradar.app.ui.screens

import android.app.Application
import android.content.ContentValues
import android.content.Intent
import android.provider.MediaStore
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import de.tankradar.app.data.PriceRepository
import de.tankradar.app.data.remote.FuelType
import de.tankradar.app.domain.ComplaintPdf
import de.tankradar.app.domain.PriceChangeCase
import de.tankradar.app.ui.Format
import de.tankradar.app.ui.theme.TankRadarDanger
import de.tankradar.app.ui.theme.TankRadarTextDim
import de.tankradar.app.ui.theme.TankRadarWarning
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ComplianceUiState(
    val cases: List<PriceChangeCase> = emptyList(),
    val isLoading: Boolean = true,
    val exportMessage: String? = null,
)

class ComplianceViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = PriceRepository.create(app)
    private val state = MutableStateFlow(ComplianceUiState())
    val uiState: StateFlow<ComplianceUiState> = state.asStateFlow()

    fun load() = viewModelScope.launch {
        state.value = state.value.copy(isLoading = true)
        val cases = withContext(Dispatchers.IO) { repository.priceChangeCases() }
        state.value = state.value.copy(cases = cases, isLoading = false)
    }

    fun dismissMessage() {
        state.value = state.value.copy(exportMessage = null)
    }

    /**
     * Write the evidence PDF into the device's Downloads folder.
     *
     * MediaStore is used rather than a private file so the document is where the
     * user expects it and can be attached to an email without the app being
     * involved; on API 29+ that needs no storage permission.
     */
    fun exportPdf() = viewModelScope.launch {
        val cases = state.value.cases
        val context = getApplication<Application>()
        val fileName = "TankRadar_Beschwerdeanlage_" +
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm", Locale.ROOT)) +
            ".pdf"

        val message = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = ComplaintPdf.build(cases)
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: error("Kein Speicherplatz im Downloads-Ordner verfügbar")
                try {
                    resolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: error("Datei konnte nicht geschrieben werden")
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } catch (error: Exception) {
                    // A pending entry left behind stays invisible to the user forever.
                    resolver.delete(uri, null, null)
                    throw error
                }
                "$fileName im Ordner \"Downloads\" gespeichert"
            }.getOrElse { "Export fehlgeschlagen: ${it.message}" }
        }
        state.value = state.value.copy(exportMessage = message)
    }
}

@Composable
fun ComplianceScreen(
    onShowMessage: (String) -> Unit,
    contentPadding: PaddingValues,
    viewModel: ComplianceViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(state.exportMessage) {
        state.exportMessage?.let {
            onShowMessage(it)
            viewModel.dismissMessage()
        }
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = contentPadding,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    "Preis-Prüffälle",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "Tatsächliche Preisänderungen nach 12:00 Uhr, letzte 30 Tage.",
                    color = TankRadarTextDim,
                    fontSize = 13.sp,
                )
            }
        }

        item {
            Card(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = TankRadarWarning.copy(alpha = 0.12f)
                ),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text(
                    "Wichtiger Hinweis: Eine Preisänderung nach 12:00 Uhr ist nicht automatisch " +
                        "rechtswidrig. TankRadar sammelt nur die Nachweisdaten für eine sachliche " +
                        "Prüfung oder Beschwerde.",
                    Modifier.padding(12.dp),
                    color = TankRadarWarning,
                    fontSize = 12.sp,
                )
            }
        }

        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = viewModel::exportPdf,
                    enabled = !state.isLoading,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Beschwerdeanlage als PDF")
                }
            }
        }

        item {
            Row(Modifier.padding(horizontal = 16.dp)) {
                Text(
                    if (state.isLoading) "Wird ausgewertet…" else "${state.cases.size} Prüffälle",
                    color = TankRadarTextDim,
                    fontSize = 13.sp,
                )
            }
        }

        if (!state.isLoading && state.cases.isEmpty()) {
            item {
                EmptyState(
                    title = "Keine Prüffälle",
                    body = "In den letzten 30 Tagen wurde keine Preisänderung nach 12:00 Uhr " +
                        "aufgezeichnet. Je länger TankRadar Preise sammelt, desto aussagekräftiger " +
                        "wird diese Auswertung.",
                )
            }
        }

        items(state.cases, key = { it.eventId }) { case ->
            CaseCard(case)
        }

        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun CaseCard(case: PriceChangeCase) {
    Card(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(
                    case.eventId,
                    color = TankRadarTextDim,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(Format.dateTime(case.time), color = TankRadarTextDim, fontSize = 11.sp)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                listOfNotNull(case.brand, case.stationName).joinToString(" "),
                fontWeight = FontWeight.SemiBold,
            )
            Text(case.address, color = TankRadarTextDim, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(FuelType.labelFor(case.fuelType), fontSize = 13.sp, modifier = Modifier.weight(1f))
                Text(
                    "${Format.price(case.previousPrice)} → ${Format.price(case.price)}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.padding(horizontal = 4.dp))
                Text(
                    Format.signedPrice(case.difference),
                    color = if (case.difference > 0) TankRadarDanger else MaterialTheme.colorScheme.secondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
