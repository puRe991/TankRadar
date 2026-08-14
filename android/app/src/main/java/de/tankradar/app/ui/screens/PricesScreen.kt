package de.tankradar.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import de.tankradar.app.data.remote.FuelType
import de.tankradar.app.ui.Format
import de.tankradar.app.ui.components.Sparkline
import de.tankradar.app.ui.theme.TankRadarDanger
import de.tankradar.app.ui.theme.TankRadarSuccess
import de.tankradar.app.ui.theme.TankRadarTextDim
import de.tankradar.app.ui.theme.TankRadarWarning

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PricesScreen(
    onOpenStation: (String) -> Unit,
    onShowMessage: (String) -> Unit,
    contentPadding: PaddingValues,
    viewModel: PricesViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.message) {
        state.message?.let {
            onShowMessage(it)
            viewModel.dismissMessage()
        }
    }

    PullToRefreshBox(
        isRefreshing = state.isRefreshing,
        onRefresh = viewModel::refresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        "Spritpreise",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        buildString {
                            append("PLZ ${state.postCode}")
                            state.lastUpdate?.let { append(" · Stand ${Format.relativeAge(it)}") }
                        },
                        color = TankRadarTextDim,
                        fontSize = 13.sp,
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
                    FuelType.entries.forEach { fuelType ->
                        FilterChip(
                            selected = state.fuelType == fuelType,
                            onClick = { viewModel.setFuelType(fuelType) },
                            label = { Text(fuelType.label, fontSize = 12.sp) },
                        )
                    }
                }
            }

            item {
                Row(Modifier.padding(horizontal = 16.dp)) {
                    FilterChip(
                        selected = state.favouritesOnly,
                        onClick = viewModel::toggleFavouritesOnly,
                        label = { Text("Nur Favoriten") },
                        leadingIcon = {
                            Icon(Icons.Filled.Star, contentDescription = null, Modifier.size(16.dp))
                        },
                    )
                }
            }

            if (state.stations.isEmpty()) {
                item {
                    EmptyState(
                        title = "Noch keine Preise",
                        body = "Zieh die Liste nach unten, um Preise für PLZ ${state.postCode} " +
                            "vom ADAC zu laden. Die PLZ änderst du unter Einstellungen.",
                    )
                }
            }

            items(state.stations, key = { it.id }) { station ->
                StationCard(
                    station = station,
                    onClick = { onOpenStation(station.id) },
                    onToggleFavorite = { viewModel.toggleFavorite(station.id, !station.isFavorite) },
                )
            }
        }
    }
}

@Composable
private fun StationCard(
    station: StationRow,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(14.dp)) {
            if (station.isCheapest) {
                Box(
                    Modifier
                        .background(TankRadarSuccess.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 3.dp)
                ) {
                    Text(
                        "Bester Preis",
                        color = TankRadarSuccess,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.height(6.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    station.brand?.let {
                        Text(it.uppercase(), color = TankRadarTextDim, fontSize = 11.sp)
                    }
                    Text(
                        station.name,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    station.city?.let { Text(it, color = TankRadarTextDim, fontSize = 12.sp) }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            station.price?.let { Format.price(it) } ?: "—",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        TrendIndicator(station.price, station.previousPrice)
                    }
                    station.timestamp?.let {
                        Text(
                            Format.relativeAge(it),
                            color = TankRadarTextDim,
                            fontSize = 11.sp,
                        )
                    }
                }

                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (station.isFavorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = if (station.isFavorite) "Favorit entfernen" else "Als Favorit merken",
                        tint = if (station.isFavorite) TankRadarWarning else TankRadarTextDim,
                    )
                }
            }

            if (station.sparkline.size >= 2) {
                Spacer(Modifier.height(8.dp))
                Sparkline(
                    prices = station.sparkline,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(26.dp),
                )
            }
        }
    }
}

@Composable
private fun TrendIndicator(price: Double?, previousPrice: Double?) {
    if (price == null || previousPrice == null || price == previousPrice) return
    val rising = price > previousPrice
    Spacer(Modifier.width(4.dp))
    Text(
        text = if (rising) "▲" else "▼",
        color = if (rising) TankRadarDanger else TankRadarSuccess,
        fontSize = 14.sp,
    )
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            color = TankRadarTextDim,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
