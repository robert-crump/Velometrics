package com.velometrics.app.ui.components

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.velometrics.app.ui.navigation.Screen
import com.velometrics.app.ui.navigation.bottomNavItems
import com.velometrics.app.ui.shared.GpxSharedViewModel

@Composable
fun BottomNavBar(navController: NavController) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Detail screens aren't tabs themselves; highlight the tab they were opened from.
    val highlightedRoute = when (currentRoute) {
        Screen.SessionDetail.route -> Screen.Home.route
        Screen.RepeatedRouteDetail.route -> Screen.RoutePlanner.route
        Screen.RepeatedIntervalDetail.route -> Screen.RoutePlanner.route
        Screen.Info.route -> Screen.Settings.route
        Screen.HomeAddress.route -> Screen.Settings.route
        else -> currentRoute
    }

    val gpxSharedViewModel: GpxSharedViewModel = hiltViewModel(LocalActivity.current as ComponentActivity)
    val showGpxPoisOverlay by gpxSharedViewModel.showGpxPoisOverlay.collectAsState()

    NavigationBar {
        bottomNavItems.forEach { screen ->
            NavigationBarItem(
                icon = { Icon(screen.icon, contentDescription = screen.title) },
                label = { Text(screen.title) },
                selected = highlightedRoute == screen.route,
                onClick = {
                    // While the "POIs along .gpx" overlay is open, a tab tap closes it instead
                    // of navigating away.
                    if (showGpxPoisOverlay) {
                        gpxSharedViewModel.setGpxPoisOverlayVisible(false)
                    } else {
                        navController.navigate(screen.route) {
                            popUpTo(navController.graph.startDestinationId) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                }
            )
        }
    }
}
