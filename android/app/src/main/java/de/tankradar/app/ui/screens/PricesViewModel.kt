package de.tankradar.app.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.tankradar.app.data.PriceRepository
import de.tankradar.app.data.ScrapeOutcome
import de.tankradar.app.data.SettingsRepository
import de.tankradar.app.data.db.StationWithPrice
import de.tankradar.app.data.remote.FuelType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One row of the station list. */
data class StationRow(
    val id: String,
    val name: String,
    val brand: String?,
    val city: String?,
    val price: Double?,
    val previousPrice: Double?,
    val timestamp: Long?,
    val isFavorite: Boolean,
    val isCheapest: Boolean,
    val sparkline: List<Double>,
)

data class PricesUiState(
    val fuelType: FuelType = FuelType.DEFAULT,
    val postCode: String = "",
    val stations: List<StationRow> = emptyList(),
    val favouritesOnly: Boolean = false,
    val isRefreshing: Boolean = false,
    val lastUpdate: Long? = null,
    val message: String? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class PricesViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = PriceRepository.create(app)
    private val settingsRepository = SettingsRepository(app)

    private val refreshing = MutableStateFlow(false)
    private val favouritesOnly = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    private val stationRows = settingsRepository.settings
        .map { it.fuelType }
        .flatMapLatest { fuelType ->
            combine(
                repository.observeStations(fuelType),
                repository.observeRecent(fuelType, hours = 24),
            ) { stations, recent ->
                val sparklines = recent
                    .groupBy { it.stationId }
                    .mapValues { (_, rows) -> rows.sortedBy { it.timestamp }.map { it.price } }
                buildRows(stations, sparklines)
            }
        }

    val uiState: StateFlow<PricesUiState> = combine(
        settingsRepository.settings,
        stationRows,
        favouritesOnly,
        refreshing,
        message,
    ) { settings, rows, favouritesOnlyValue, isRefreshing, currentMessage ->
        PricesUiState(
            fuelType = settings.fuelType,
            postCode = settings.postCode,
            stations = if (favouritesOnlyValue) rows.filter { it.isFavorite } else rows,
            favouritesOnly = favouritesOnlyValue,
            isRefreshing = isRefreshing,
            lastUpdate = rows.mapNotNull { it.timestamp }.maxOrNull(),
            message = currentMessage,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PricesUiState())

    private fun buildRows(
        stations: List<StationWithPrice>,
        sparklines: Map<String, List<Double>>,
    ): List<StationRow> {
        val cheapestId = stations.filter { it.price != null }.minByOrNull { it.price!! }?.id
        return stations
            .map { station ->
                StationRow(
                    id = station.id,
                    name = station.name,
                    brand = station.brand,
                    city = station.city,
                    price = station.price,
                    previousPrice = station.previousPrice,
                    timestamp = station.timestamp,
                    isFavorite = station.isFavorite,
                    isCheapest = station.id == cheapestId && station.price != null,
                    sparkline = sparklines[station.id].orEmpty(),
                )
            }
            // Favourites first, then by price; stations without a price for this
            // fuel type sink to the bottom instead of pretending to be free.
            .sortedWith(
                compareByDescending<StationRow> { it.isFavorite }
                    .thenBy { it.price ?: Double.MAX_VALUE }
            )
    }

    fun setFuelType(fuelType: FuelType) = viewModelScope.launch {
        settingsRepository.setFuelType(fuelType)
    }

    fun toggleFavouritesOnly() {
        favouritesOnly.value = !favouritesOnly.value
    }

    fun toggleFavorite(stationId: String, favorite: Boolean) = viewModelScope.launch {
        repository.setFavorite(stationId, favorite)
    }

    fun dismissMessage() {
        message.value = null
    }

    fun refresh() = viewModelScope.launch {
        if (refreshing.value) return@launch
        refreshing.value = true
        try {
            val settings = settingsRepository.settings.first()
            message.value = when (val outcome = repository.refresh(settings.postCode, settings.radiusKm)) {
                is ScrapeOutcome.Success ->
                    "${outcome.stationsSeen} Tankstellen aktualisiert, ${outcome.pricesStored} neue Preise"
                is ScrapeOutcome.Failure -> "Aktualisierung fehlgeschlagen: ${outcome.message}"
            }
            repository.pruneHistory(settings.historyRetentionDays)
        } finally {
            refreshing.value = false
        }
    }

    val favouritesOnlyState: StateFlow<Boolean> = favouritesOnly.asStateFlow()
}
