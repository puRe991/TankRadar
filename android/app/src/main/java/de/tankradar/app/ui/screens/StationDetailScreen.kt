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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.tankradar.app.data.PriceRepository
import de.tankradar.app.data.SettingsRepository
import de.tankradar.app.data.remote.FuelType
import de.tankradar.app.domain.Forecast
import de.tankradar.app.domain.ForecastResult
import de.tankradar.app.ui.Format
import de.tankradar.app.ui.components.ChartPoint
import de.tankradar.app.ui.components.PriceChart
import de.tankradar.app.ui.theme.TankRadarSuccess
import de.tankradar.app.ui.theme.TankRadarTextDim
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import androidx.lifecycle.viewModelScope

data class StationDetailUiState(
    val name: String = "",
    val brand: String? = null,
    val address: String = "",
    val fuelType: FuelType = FuelType.DEFAULT,
    val currentPrice: Double? = null,
    val averagePrice: Double? = null,
    val minPrice: Double? = null,
    val maxPrice: Double? = null,
    val history: List<ChartPoint> = emptyList(),
    val forecast: ForecastResult? = null,
    val pointCount: Int = 0,
)

@OptIn(ExperimentalCoroutinesApi::class)
class StationDetailViewModel(
    app: Application,
    private val stationId: String,
) : AndroidViewModel(app) {

    private val repository = PriceRepository.create(app)
    private val settingsRepository = SettingsRepository(app)

    val uiState: StateFlow<StationDetailUiState> = settingsRepository.settings
        .map { it.fuelType }
        .flatMapLatest { fuelType ->
            combine(
                repository.observeStation(stationId),
                repository.observeHistory(stationId, fuelType, days = 30),
            ) { station, history ->
                val points = history.map {
                    ChartPoint(Format.toLocalDateTime(it.timestamp), it.price)
                }
                val prices = history.map { it.price }
                StationDetailUiState(
                    name = station?.name.orEmpty(),
                    brand = station?.brand,
                    address = listOfNotNull(
                        listOfNotNull(station?.street, station?.houseNumber).joinToString(" ").ifBlank { null },
                        listOfNotNull(station?.postCode, station?.city).joinToString(" ").ifBlank { null },
                    ).joinToString(", "),
                    fuelType = fuelType,
                    currentPrice = prices.lastOrNull(),
                    averagePrice = prices.average().takeIf { prices.isNotEmpty() },
                    minPrice = prices.minOrNull(),
                    maxPrice = prices.maxOrNull(),
                    history = points,
                    forecast = Forecast.predictNext24h(
                        history.map {
                            de.tankradar.app.domain.PricePoint(
                                Format.toLocalDateTime(it.timestamp),
                                it.price,
                            )
                        }
                    ),
                    pointCount = history.size,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StationDetailUiState())
}

@Composable
fun StationDetailScreen(
    stationId: String,
    contentPadding: PaddingValues,
) {
    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as Application
    val viewModel: StationDetailViewModel = viewModel(
        key = stationId,
        factory = object : androidx.lifecycle.ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                StationDetailViewModel(app, stationId) as T
        },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(contentPadding)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            state.brand?.let { Text(it.uppercase(), color = TankRadarTextDim, fontSize = 12.sp) }
            Text(
                state.name,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            if (state.address.isNotBlank()) {
                Text(state.address, color = TankRadarTextDim, fontSize = 13.sp)
            }
            Text(state.fuelType.label, color = TankRadarTextDim, fontSize = 13.sp)
        }

        DetailCard("Preisverlauf (30 Tage)") {
            PriceChart(
                history = state.history,
                forecast = state.forecast?.points.orEmpty().map {
                    ChartPoint(it.time, it.price, isForecast = true)
                },
                bestTime = state.forecast?.bestTime,
                bestPrice = state.forecast?.bestPrice,
            )
        }

        DetailCard("Tankzeit-Prognose (24 h)") {
            val forecast = state.forecast
            if (forecast == null) {
                Text(
                    "Für eine Prognose braucht TankRadar mindestens ${Forecast.MIN_DATA_POINTS} " +
                        "Messwerte an dieser Tankstelle — bisher sind es ${state.pointCount}.",
                    color = TankRadarTextDim,
                    fontSize = 13.sp,
                )
            } else {
                Text(
                    Format.dayAndTime(forecast.bestTime),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = TankRadarSuccess,
                )
                Text(
                    "erwartet ${Format.priceWithUnit(forecast.bestPrice)} " +
                        "(${Format.price(forecast.bestPriceLower)} – ${Format.price(forecast.bestPriceUpper)})",
                    color = TankRadarTextDim,
                    fontSize = 13.sp,
                )
                Spacer(Modifier.height(6.dp))
                Text("Modell: ${forecast.modelName}", color = TankRadarTextDim, fontSize = 12.sp)
            }
        }

        DetailCard("Kennzahlen (30 Tage)") {
            StatRow("Aktuell", state.currentPrice)
            StatRow("Durchschnitt", state.averagePrice)
            StatRow("Günstigster Wert", state.minPrice)
            StatRow("Teuerster Wert", state.maxPrice)
        }

        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun DetailCard(title: String, content: @Composable () -> Unit) {
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

@Composable
private fun StatRow(label: String, value: Double?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = TankRadarTextDim, modifier = Modifier.weight(1f), fontSize = 13.sp)
        Text(
            value?.let { Format.priceWithUnit(it) } ?: "—",
            fontWeight = FontWeight.SemiBold,
            fontSize = 13.sp,
        )
    }
}
