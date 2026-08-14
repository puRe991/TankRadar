package de.tankradar.app.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import de.tankradar.app.ui.screens.ComplianceScreen
import de.tankradar.app.ui.screens.LogbookScreen
import de.tankradar.app.ui.screens.PricesScreen
import de.tankradar.app.ui.screens.SettingsScreen
import de.tankradar.app.ui.screens.StationDetailScreen
import kotlinx.coroutines.launch

private data class TopLevelDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val topLevelDestinations = listOf(
    TopLevelDestination("prices", "Preise", Icons.Filled.LocalGasStation),
    TopLevelDestination("logbook", "Tagebuch", Icons.AutoMirrored.Filled.ListAlt),
    TopLevelDestination("compliance", "Prüffälle", Icons.Filled.WarningAmber),
    TopLevelDestination("settings", "Einstellungen", Icons.Filled.Settings),
)

@Composable
fun TankRadarNavigation() {
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination

    val showMessage: (String) -> Unit = { message ->
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                topLevelDestinations.forEach { destination ->
                    NavigationBarItem(
                        selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true,
                        onClick = {
                            navController.navigate(destination.route) {
                                // Keep a single copy of each tab and restore where the
                                // user left off instead of stacking duplicates.
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { scaffoldPadding ->
        NavHost(
            navController = navController,
            startDestination = "prices",
            modifier = Modifier,
        ) {
            composable("prices") {
                PricesScreen(
                    onOpenStation = { navController.navigate("station/$it") },
                    onShowMessage = showMessage,
                    contentPadding = scaffoldPadding.withVerticalSpacing(),
                )
            }
            composable("logbook") {
                LogbookScreen(contentPadding = scaffoldPadding.withVerticalSpacing())
            }
            composable("compliance") {
                ComplianceScreen(
                    onShowMessage = showMessage,
                    contentPadding = scaffoldPadding.withVerticalSpacing(),
                )
            }
            composable("settings") {
                SettingsScreen(contentPadding = scaffoldPadding.withVerticalSpacing())
            }
            composable("station/{stationId}") { entry ->
                StationDetailScreen(
                    stationId = entry.arguments?.getString("stationId").orEmpty(),
                    contentPadding = scaffoldPadding.withVerticalSpacing(),
                )
            }
        }
    }
}

/**
 * The screens scroll under the status and navigation bars, so each one applies the
 * scaffold insets itself and adds a little breathing room at the top and bottom.
 */
@Composable
private fun PaddingValues.withVerticalSpacing(): PaddingValues {
    val layoutDirection = LocalLayoutDirection.current
    return PaddingValues(
        start = calculateStartPadding(layoutDirection),
        end = calculateEndPadding(layoutDirection),
        top = calculateTopPadding() + 12.dp,
        bottom = calculateBottomPadding() + 12.dp,
    )
}
