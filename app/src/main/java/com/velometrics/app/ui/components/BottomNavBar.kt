package com.velometrics.app.ui.components

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.runtime.getValue
import com.velometrics.app.ui.navigation.Screen
import com.velometrics.app.ui.navigation.bottomNavItems

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
        Screen.AllTimeStats.route -> Screen.Home.route
        else -> currentRoute
    }

    NavigationBar {
        bottomNavItems.forEach { screen ->
            NavigationBarItem(
                icon = { Icon(screen.icon, contentDescription = screen.title) },
                label = { Text(screen.title) },
                selected = highlightedRoute == screen.route,
                onClick = {
                    if (highlightedRoute == screen.route && currentRoute != screen.route) {
                        // Already on a detail screen opened from this tab (e.g. Session Detail
                        // opened from Home): tapping the tab it's highlighted under means "take
                        // me back", so just pop back to the existing tab instance rather than
                        // navigating to a freshly-restored copy of it.
                        navController.popBackStack(screen.route, inclusive = false)
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
